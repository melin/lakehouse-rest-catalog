/**
 * `utils/sensitivityTable.js` 的用例：表详情「敏感数据」页签的行合成与四条判据。
 *
 * <p>为什么这些算错都不报错，只给出一句**看起来很确定**的结论：
 *
 *   · 行骨架若来自标签而不是 schema → 没被识别出来的列从页面上消失，
 *     读的人看到的是「全部分类完了」，而实际是「大部分没扫过」（设计稿 §13）；
 *   · 级别若按 `ordinal()` 或字符串比大小 → `IMPORTANT` / `CORE` 排在 `L1` 之后，
 *     「表级最高级别」会显示成 L1——两档最严的人工认定被算成最低；
 *   · 来源若不分优先级 → 一列同时有人工与自动标签时读成「自动」，
 *     「人工覆盖过」这件事从结论里被抹掉；
 *   · `protected` 若把「有标签」也算进去 → 一句「机密列都已受保护」就把
 *     「只是打了标、策略还没接」说成了安全（标签驱动脱敏属 P3）。
 *
 * <p>这几条在界面上都验不到（需要特定的标签 / 策略组合才会显现），
 * 因此判据抽成纯函数放在这里，用「构造出来的输入」去测。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  LEVEL_RANK,
  MANUAL_ONLY_LEVELS,
  buildRows,
  exposureVerdict,
  levelRank,
  levelTagType,
  sourceLabel,
  sourceTagType,
} from '../../src/utils/sensitivityTable.js'

/** 服务端 `SensitivityEnums.SensitivityLevel` 的声明顺序（ordinal）。 */
const DECLARED_ORDER = ['L1', 'L2', 'L3', 'L4', 'IMPORTANT', 'CORE']

/** 一张表：三列，其中一列没被任何标签命中。 */
const COLUMNS = [
  { name: 'id', type: 'BIGINT' },
  { name: 'id_card', type: 'VARCHAR(18)' },
  { name: 'remark', type: 'VARCHAR(255)' },
]

test('级别序数：IMPORTANT / CORE 高于 L4，不跟声明顺序走', () => {
  assert.deepEqual(Object.keys(LEVEL_RANK), DECLARED_ORDER)
  // 声明顺序里 IMPORTANT 排在 L4 之后是「后加的取值」，不是「更不严重」。
  // 按 rank 比：这两档必须大于 L4
  assert.ok(levelRank('IMPORTANT') > levelRank('L4'))
  assert.ok(levelRank('CORE') > levelRank('IMPORTANT'))
  assert.ok(levelRank('L4') > levelRank('L3'))
  // 不是分级值（如 owner.team）给 0，而不是 NaN 或抛错
  assert.equal(levelRank('owner.team'), 0)
  assert.equal(levelRank(''), 0)
  assert.equal(levelRank(undefined), 0)
})

test('人工认定的两档只有 IMPORTANT 与 CORE', () => {
  assert.deepEqual(MANUAL_ONLY_LEVELS, ['IMPORTANT', 'CORE'])
})

test('色与文案映射：两档人工级别用 danger，来源「人工」不复用自动的颜色', () => {
  for (const level of ['L4', ...MANUAL_ONLY_LEVELS]) {
    assert.equal(levelTagType(level), 'danger', `${level} 应当是 danger`)
  }
  assert.equal(levelTagType('L2'), 'primary')
  // 认不出的级别不该得到一个像「有结论」的颜色
  assert.equal(levelTagType(''), 'info')
  assert.equal(sourceLabel('MANUAL'), '人工')
  assert.equal(sourceLabel('AUTO'), '自动')
  assert.equal(sourceLabel('INHERITED'), '继承')
  assert.equal(sourceTagType('MANUAL'), 'success')
  assert.notEqual(sourceTagType('MANUAL'), sourceTagType('AUTO'))
})

test('行骨架来自 schema：没被标签命中的列也在，且 classified 为 false', () => {
  const rows = buildRows(
    COLUMNS,
    [{ column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' }],
    [],
    [],
  )
  assert.deepEqual(rows.map((row) => row.name), ['id', 'id_card', 'remark'])
  assert.deepEqual(rows.map((row) => row.classified), [false, true, false])
  assert.equal(rows[1].level, 'L4')
  assert.equal(rows[1].tags.length, 1)
})

test('表级标签被跳过，不落成一行', () => {
  // 表级标签（column=''、tableLevel=true）说的是「这张表最敏感的一列有多敏感」，
  // 不是某一列的结论。挂到列行上会凭空多出一列带标签——那一列并不存在
  const rows = buildRows(
    COLUMNS,
    [
      { column: '', tableLevel: true, level: 'L4', tagKey: 'sensitivity.level', source: 'MANUAL' },
      { column: 'id_card', tableLevel: false, level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' },
    ],
    [],
    [],
  )
  assert.equal(rows.length, COLUMNS.length)
  assert.equal(rows.filter((row) => row.tags.length > 0).length, 1)
})

test('一列多标签：级别取最高，来源取最高优先级（人工 > 自动 > 继承）', () => {
  const [, row] = buildRows(
    COLUMNS,
    [
      { column: 'id_card', level: 'L2', tagKey: 'PII.姓名', source: 'AUTO' },
      { column: 'id_card', level: 'IMPORTANT', tagKey: 'PII.身份证', source: 'MANUAL' },
    ],
    [],
    [],
  )
  assert.equal(row.tags.length, 2)
  // 取最高级别——IMPORTANT 的 rank(5) 大于 L2(2)
  assert.equal(row.level, 'IMPORTANT')
  // 来源表达的是「结论的权威性」：有人工标签就说明这列靠人管着
  assert.equal(row.source, 'MANUAL')
})

test('只有自动与继承时取自动；只有继承时取继承', () => {
  const onlyInherited = buildRows(COLUMNS,
    [{ column: 'remark', level: 'L2', tagKey: 'PII.备注', source: 'INHERITED' }])[2]
  assert.equal(onlyInherited.source, 'INHERITED')
  const autoAndInherited = buildRows(COLUMNS, [
    { column: 'remark', level: 'L2', tagKey: 'PII.备注', source: 'INHERITED' },
    { column: 'remark', level: 'L2', tagKey: 'PII.备注2', source: 'AUTO' },
  ])[2]
  assert.equal(autoAndInherited.source, 'AUTO')
})

test('有标签但没级别也算已分类（与覆盖率分子同口径）', () => {
  // `owner.team` 这类自由标签占一格分类名额，但没有敏感级别
  const [, row] = buildRows(COLUMNS,
    [{ column: 'id_card', level: '', tagKey: 'owner.team', source: 'MANUAL' }])
  assert.equal(row.classified, true)
  assert.equal(row.level, '')
  assert.equal(levelRank(row.level), 0)
})

test('protected 只认显式策略：有标签不算已保护', () => {
  const rows = buildRows(
    COLUMNS,
    [{ column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' }],
    [{ column: 'remark', sql: 'sha256(remark) as remark' }],
    ['id'],
  )
  const byName = new Map(rows.map((row) => [row.name, row]))
  // 有 L4 标签、没有脱敏动作 → 不 protected：标签驱动脱敏属 P3
  assert.equal(byName.get('id_card').protected, false)
  assert.equal(byName.get('id_card').maskSql, '')
  // 显式脱敏
  assert.equal(byName.get('remark').protected, true)
  assert.equal(byName.get('remark').maskSql, 'sha256(remark) as remark')
  // 整列不可见
  assert.equal(byName.get('id').hidden, true)
  assert.equal(byName.get('id').protected, true)
  // 两者都没有
  assert.equal(byName.get('id_card').hidden, false)
})

test('空表与空标签都不炸：给空行数组而不是 undefined', () => {
  assert.deepEqual(buildRows([], [], [], []), [])
  assert.deepEqual(buildRows(undefined, undefined, undefined, undefined), [])
})

test('判据：有机密列仍是明文 → risk，且结论说的是「缺什么」', () => {
  const rows = buildRows(COLUMNS, [
    { column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' },
    // 两列没分类
  ])
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.tone, 'risk')
  assert.equal(verdict.secretTotal, 1)
  assert.equal(verdict.secretProtected, 0)
  assert.equal(verdict.unclassifiedTotal, 2)
  assert.equal(verdict.topLevel, 'L4')
  assert.equal(verdict.totalColumns, 3)
  assert.equal(verdict.classifiedTotal, 1)
  assert.match(verdict.verdict, /^不可对外：/)
  assert.match(verdict.verdict, /1 个机密列（L4 \/ 重要 \/ 核心）仍是明文/)
  assert.match(verdict.verdict, /2 列还没被分类/)
})

test('判据：风险与缺口同时成立时仍是 risk，不被后面的分支降档', () => {
  // 三列都命中、但都没脱敏：既「有机密列是明文」又「有 L2 列没脱敏」。
  // 分档写成嵌套三元时这里会退化成 warn（红转黄），是最容易漏的一档
  const rows = buildRows(COLUMNS, [
    { column: 'id', level: 'L2', tagKey: 'PII.主键', source: 'AUTO' },
    { column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' },
    { column: 'remark', level: 'L3', tagKey: 'PII.备注', source: 'AUTO' },
  ])
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.secretTotal, 1)
  assert.equal(verdict.exposedTotal, 3)
  assert.equal(verdict.tone, 'risk')
  // 两条缺口都要在结论里，不能因为取了 risk 就少说一条
  assert.match(verdict.verdict, /仍是明文/)
  assert.match(verdict.verdict, /没有脱敏动作/)
})

test('判据：机密列全部受保护但仍有未分类列 → warn，且说「可内部共享，对外前需补齐」', () => {
  const rows = buildRows(
    COLUMNS,
    [{ column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' }],
    [{ column: 'id_card', sql: 'mask(id_card)' }],
    [],
  )
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.tone, 'warn')
  assert.equal(verdict.secretProtected, verdict.secretTotal)
  // 机密列全受保护不等于可以对外：未分类列那一句**必须**在结论里
  // （覆盖率是分母问题，不能靠沉默掩盖；原型 #table 的结论栏就是这个口径）
  assert.match(verdict.verdict, /^可内部共享，对外前需补齐：/)
  assert.match(verdict.verdict, /还没被分类/)
})

test('判据：全部分类且没有明文高敏列 → ok', () => {
  const rows = buildRows(
    COLUMNS,
    [
      { column: 'id', level: 'L1', tagKey: 'PII.主键', source: 'AUTO' },
      { column: 'id_card', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' },
      { column: 'remark', level: 'L1', tagKey: 'PII.备注', source: 'AUTO' },
    ],
    [{ column: 'id_card', sql: 'mask(id_card)' }],
    [],
  )
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.tone, 'ok')
  assert.equal(verdict.unclassifiedTotal, 0)
  assert.equal(verdict.exposedTotal, 0)
  assert.match(verdict.verdict, /未发现缺口/)
})

test('L2 及以上没脱敏的列单独计数：它们不是机密，但也不是能随便给的', () => {
  const rows = buildRows(COLUMNS, [
    { column: 'remark', level: 'L2', tagKey: 'PII.备注', source: 'AUTO' },
  ])
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.secretTotal, 0)
  assert.equal(verdict.exposedTotal, 1)
  assert.equal(verdict.tone, 'warn')
  assert.match(verdict.verdict, /1 个 L2 及以上的列没有脱敏动作/)
})

test('L1 未脱敏不算暴露；只有 L1 且全分类 → ok', () => {
  const rows = buildRows(COLUMNS, [
    { column: 'id', level: 'L1', tagKey: 'PII.主键', source: 'AUTO' },
    { column: 'id_card', level: 'L1', tagKey: 'PII.裸文本', source: 'AUTO' },
    { column: 'remark', level: 'L1', tagKey: 'PII.备注', source: 'AUTO' },
  ])
  const verdict = exposureVerdict(rows)
  assert.equal(verdict.exposedTotal, 0)
  assert.equal(verdict.tone, 'ok')
})

test('空表给一句实话，而不是一句「安全」', () => {
  const verdict = exposureVerdict([])
  assert.equal(verdict.totalColumns, 0)
  assert.equal(verdict.tone, 'ok')
  assert.match(verdict.verdict, /没有可判定的列/)
})

test('表级最高级别按 rank 取：IMPORTANT 压过 L4', () => {
  const rows = buildRows(COLUMNS, [
    { column: 'id', level: 'L4', tagKey: 'PII.身份证', source: 'AUTO' },
    { column: 'id_card', level: 'IMPORTANT', tagKey: 'PII.重要数据', source: 'MANUAL' },
  ])
  assert.equal(exposureVerdict(rows).topLevel, 'IMPORTANT')
})
