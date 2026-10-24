package com.agentruntime.local;

/**
 * 本地模型后端统一接口。
 *
 * <p>实现类必须做到：不可用时 {@link #available()} 返回 false 且不抛异常，
 * 调用失败返回带 code 的 {@link LocalModelReply}。上层据此做降级，不依赖异常流控制。
 */
public interface LocalModelProvider {

    /** 后端标识，出现在审计与结果里。 */
    String id();

    /** 后端是否可用（配置正确且依赖到位）。 */
    boolean available();

    /** 是否支持图像输入。 */
    boolean supportsVision();

    /** 执行一次补全。实现不得抛受检异常，失败一律转成 LocalModelReply.fail。 */
    LocalModelReply complete(LocalModelRequest request);
}
