/*
 * 管理语句的语法扩展。
 *
 * 这份语法只描述「管理 Paimon Rest Catalog 的主体、角色与授权」这一组语句，
 * 不重复描述 SQL 查询。运行时它先于 Spark 原生解析器被尝试：
 * 解析成功则执行管理操作，解析失败（ParseCancellationException）则原样交回
 * Spark 的 ParserInterface，因此普通 SQL 的行为不受影响。
 *
 * 之所以必须自带语法：Spark 3.5 的 SqlBase.g4 是封闭的，statement 规则没有
 * 兜底分支，`CREATE PRINCIPAL x` 这类语句在词法/语法阶段就会失败，
 * 无法通过 visitor 拦截。详见 paimon-rest-spark/pom.xml 的说明。
 *
 * 关键字用「字母片段」写法实现大小写不敏感（A: [aA]; CREATE: C R E A T E;），
 * 而不用 ANTLR 的 caseInsensitive 选项：后者对取反字符集的支持有限，
 * 而标识符需要 `~'`'` 这类取反集合。
 *
 * 注意：不要在这里写 @header 声明 package。antlr4-maven-plugin 会依据本文件
 * 相对 src/main/antlr4 的目录层级自动生成 package 语句，两者同时存在会产生
 * 重复的 package 声明而编译失败。包名由目录结构决定。
 */
grammar ManagementSql;

singleStatement
    : statement SEMICOLON* EOF
    ;

statement
    : createPrincipal
    | dropPrincipal
    | alterPrincipal
    | resetPrincipal
    | createPrincipalRole
    | dropPrincipalRole
    | alterPrincipalRole
    | grantPrincipalRole
    | revokePrincipalRole
    | grantPermissions
    | revokePermissions
    | showPrincipals
    | showPrincipalRoles
    | showPermissions
    | showCatalogs
    ;

// ---------------------------------------------------------------------- DDL：主体
createPrincipal
    : CREATE PRINCIPAL (IF NOT EXISTS)? name=identifier propertyList?
    ;

dropPrincipal
    : DROP PRINCIPAL (IF EXISTS)? name=identifier
    ;

alterPrincipal
    : ALTER PRINCIPAL name=identifier (setPropertyList | unsetPropertyList)
    ;

/** RESET 与 ROTATE 同义：都重置主体凭据并返回一次性的明文密钥。 */
resetPrincipal
    : (RESET | ROTATE) PRINCIPAL name=identifier
    ;

// ---------------------------------------------------------------------- DDL：principal role
createPrincipalRole
    : CREATE PRINCIPAL ROLE (IF NOT EXISTS)? name=identifier propertyList?
    ;

dropPrincipalRole
    : DROP PRINCIPAL ROLE (IF EXISTS)? name=identifier
    ;

alterPrincipalRole
    : ALTER PRINCIPAL ROLE name=identifier (setPropertyList | unsetPropertyList)
    ;

// ---------------------------------------------------------------------- DCL：角色装配
/*
 * 只保留 principal role 的装配（主体 ↔ principal role）。
 * catalog role 相关的语法已全部移除——建档（CREATE / DROP / ALTER CATALOG ROLE）、
 * 装配（GRANT / REVOKE CATALOG ROLE）、清点（SHOW CATALOG ROLES / SHOW GRANTS）
 * 共 7 条规则。控制台既没有它的页面，SQL 也不留入口，它只剩管理 API
 * （`/catalogs/{c}/catalog-roles/**`）一条路。判定层不受影响——存量角色、
 * 装配关系与资源授权都还在库里照常生效。
 */
grantPrincipalRole
    : GRANT PRINCIPAL ROLE role=identifier TO PRINCIPAL principal=identifier
    ;

revokePrincipalRole
    : REVOKE PRINCIPAL ROLE role=identifier FROM PRINCIPAL principal=identifier
    ;

// ---------------------------------------------------------------------- DCL：资源授权
/*
 * 与「权限管理」页同一套语义（Paimon 官方权限模型）：赋权身份是
 * (资源, access, principal) 三元组，授权直接面向 principal **不透明字符串**
 * （`user:alice` / `role:sales_writer` / `group:eng`），不经过 catalog role ——
 * 页面能表达的授权，SQL 就能表达，反之亦然。
 *
 * 目标 catalog 不单独书写，而是从资源描述中取得：`ON TABLE ns.t IN CATALOG paimon`
 * 已明确指出 catalog 是 paimon，`ON CATALOG paimon` 同理。
 *
 * ROUTE 到 `/v1/{prefix}/permissions/{grant,revoke}`，与页面同一条链路。
 */
grantPermissions
    : GRANT accessList ON permissionResource TO grantee (UNTIL expire=STRING)?
    ;

revokePermissions
    : REVOKE accessList ON permissionResource FROM grantee
    ;

/*
 * access 的取值是否合法由服务端判定（官方 12 项词表，各资源类型允许的取值不同），
 * 语法层只认形状，不复制一份词表。
 *
 * `GRANT` / `ALTER` / `DROP` 既是官方词表里的 access 取值，也是本语法的关键字，
 * 因此显式列出——否则 `GRANT ALTER ON DATABASE ...` 会卡在词法阶段。
 */
accessList
    : access (COMMA access)*
    ;

access
    : identifier
    | GRANT
    | ALTER
    | DROP
    ;

/*
 * 授权对象。前三种是官方约定的 principal 写法（`user:` / `role:` / `group:` 前缀，
 * 与页面下拉里的候选同源），第四种原样透传，给不在约定里的主体留口子。
 *
 * 四个分支的名称各用一个标签名（而不是共用一个 `name`）：它们分别是规则上下文与
 * 词法记号，共用标签会让 ANTLR 把字段类型退化成 `Object`，取值处就得再做一次转换。
 */
grantee
    : USER userName=identifier
    | ROLE roleName=identifier
    | GROUP groupName=identifier
    | PRINCIPAL principalName=STRING
    ;

/*
 * 八种资源定位器，与官方 ResourceType 一一对应（`_ALL` 后缀表示
 * 「该资源及其此后新建的后代」这一显式作用域）。
 *
 * 拆成八个具名子规则而不是写成八个内联备选：父规则只要 `|` 串起来，
 * 访问器就能像读旧语法那样按 `ctx.tableResource()` 取用，不必对运行时类型做匹配。
 */
permissionResource
    : catalogResource
    | catalogAllResource
    | databaseResource
    | databaseAllResource
    | tableResource
    | viewResource
    | functionResource
    | columnResource
    ;

catalogResource
    : CATALOG catalog=identifier
    ;

catalogAllResource
    : CATALOG_ALL catalog=identifier
    ;

databaseResource
    : DATABASE database=identifier IN CATALOG catalog=identifier
    ;

databaseAllResource
    : DATABASE_ALL database=identifier IN CATALOG catalog=identifier
    ;

tableResource
    : TABLE objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

viewResource
    : VIEW objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

functionResource
    : FUNCTION objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

columnResource
    : COLUMN objectName=multipartIdentifier columnScope IN CATALOG catalog=identifier
    ;

/** 列范围：默认白名单，`EXCEPT` 转成黑名单。两者互斥（官方要求恰好一个非空）。 */
columnScope
    : LPAREN columnName (COMMA columnName)* RPAREN
    | EXCEPT LPAREN columnName (COMMA columnName)* RPAREN
    ;

columnName
    : identifier
    ;

// ---------------------------------------------------------------------- SHOW
showPrincipals
    : SHOW PRINCIPALS (FOR PRINCIPAL ROLE principalRole=identifier)?
    ;

showPrincipalRoles
    : SHOW PRINCIPAL ROLES (FOR PRINCIPAL principal=identifier)?
    ;

showPermissions
    : SHOW PERMISSIONS IN CATALOG catalog=identifier (FOR grantee)?
    ;

showCatalogs
    : SHOW CATALOGS
    ;

// ---------------------------------------------------------------------- 公共片段
propertyList
    : PROPERTIES LPAREN property (COMMA property)* RPAREN
    ;

setPropertyList
    : SET PROPERTIES LPAREN property (COMMA property)* RPAREN
    ;

unsetPropertyList
    : UNSET PROPERTIES LPAREN propertyKey (COMMA propertyKey)* RPAREN
    ;

property
    : key=propertyKey (EQ value=propertyValue)?
    ;

propertyKey
    : STRING
    | identifier
    ;

propertyValue
    : STRING
    | number
    | identifier
    ;

identifier
    : IDENTIFIER
    | BACKQUOTED_IDENTIFIER
    ;

multipartIdentifier
    : identifier (DOT identifier)*
    ;

number
    : MINUS? DECIMAL_VALUE
    ;

// ---------------------------------------------------------------------- 词法
SEMICOLON  : ';' ;
COMMA      : ',' ;
LPAREN     : '(' ;
RPAREN     : ')' ;
EQ         : '=' ;
DOT        : '.' ;
MINUS      : '-' ;

ALTER       : A L T E R ;
CATALOG     : C A T A L O G ;
CATALOG_ALL : C A T A L O G UNDERSCORE A L L ;
CATALOGS    : C A T A L O G S ;
COLUMN      : C O L U M N ;
CREATE      : C R E A T E ;
DATABASE    : D A T A B A S E ;
DATABASE_ALL: D A T A B A S E UNDERSCORE A L L ;
DROP        : D R O P ;
EXCEPT      : E X C E P T ;
EXISTS      : E X I S T S ;
FOR         : F O R ;
FROM        : F R O M ;
FUNCTION    : F U N C T I O N ;
GRANT       : G R A N T ;
GROUP       : G R O U P ;
IF          : I F ;
IN          : I N ;
NOT         : N O T ;
ON          : O N ;
PERMISSIONS : P E R M I S S I O N S ;
PRINCIPAL   : P R I N C I P A L ;
PRINCIPALS  : P R I N C I P A L S ;
PROPERTIES  : P R O P E R T I E S ;
RESET       : R E S E T ;
REVOKE      : R E V O K E ;
ROLE        : R O L E ;
ROLES       : R O L E S ;
ROTATE      : R O T A T E ;
SET         : S E T ;
SHOW        : S H O W ;
TABLE       : T A B L E ;
TO          : T O ;
UNSET       : U N S E T ;
UNTIL       : U N T I L ;
USER        : U S E R ;
VIEW        : V I E W ;

BACKQUOTED_IDENTIFIER
    : '`' ( '``' | ~'`' )* '`'
    ;

STRING
    : '\'' ( '\'\'' | ~'\'' )* '\''
    | '"' ( '""' | ~'"' )* '"'
    ;

DECIMAL_VALUE
    : DIGIT+ ( '.' DIGIT+ )?
    ;

IDENTIFIER
    : LETTER ( LETTER | DIGIT )*
    ;

fragment DIGIT  : [0-9] ;
fragment LETTER : [A-Za-z_] ;

/*
 * 下划线单独抽成片段：`DATABASE_ALL` / `CATALOG_ALL` 是官方 ResourceType 的
 * 逐字写法（词表里就叫这个名字），但关键字的其余部分都要用「字母片段」拼写，
 * 这里保持一致，免得读者以为这两个词有另一套命名规则。
 */
fragment UNDERSCORE : '_' ;

fragment A : [aA] ;
fragment B : [bB] ;
fragment C : [cC] ;
fragment D : [dD] ;
fragment E : [eE] ;
fragment F : [fF] ;
fragment G : [gG] ;
fragment H : [hH] ;
fragment I : [iI] ;
fragment J : [jJ] ;
fragment K : [kK] ;
fragment L : [lL] ;
fragment M : [mM] ;
fragment N : [nN] ;
fragment O : [oO] ;
fragment P : [pP] ;
fragment Q : [qQ] ;
fragment R : [rR] ;
fragment S : [sS] ;
fragment T : [tT] ;
fragment U : [uU] ;
fragment V : [vV] ;
fragment W : [wW] ;
fragment X : [xX] ;
fragment Y : [yY] ;
fragment Z : [zZ] ;

SPACE
    : [ \t\r\n\u000B\u000C]+ -> skip
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> skip
    ;

BRACKETED_COMMENT
    : '/*' .*? '*/' -> skip
    ;
