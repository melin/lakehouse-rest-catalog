package io.github.melin.lakehouse.rest.spark.lineage;

import java.util.List;

/**
 * 「把这批 run 送出去」这件事的抽象。
 *
 * <p>只有一个方法，是为了让上报器可以脱离 HTTP 测试：队列行为（攒批、丢弃、重试、
 * 计数）与「怎么送」是两件事，混在一起就只能起一个服务端才能验证队列。
 *
 * <p>实现方抛出的任何异常都由 {@link LineageReporter} 接住并降级为计数与日志——
 * 血缘是元数据、作业是生产，任何「为了记血缘而让 ETL 失败」的实现都是错的（§6.6）。
 */
public interface LineageIngestor {

    /**
     * 同步送出。返回即表示服务端已接受（2xx）。
     *
     * @throws Exception 网络、鉴权、服务端 4xx/5xx；调用方负责重试与降级
     */
    void ingest(List<LineageRun> runs) throws Exception;
}
