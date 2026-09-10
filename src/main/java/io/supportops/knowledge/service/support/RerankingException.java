package io.supportops.knowledge.service.support;

/** 本地重排的安全错误码，不携带正文、文件路径或本地库原始异常。 */
public final class RerankingException extends IllegalStateException {
    /** 以服务端固定错误码描述失败，调用方不得传入不可信文本。 */
    public RerankingException(String code) {
        super(code);
    }
}
