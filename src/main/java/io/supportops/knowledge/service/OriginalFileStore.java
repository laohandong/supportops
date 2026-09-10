package io.supportops.knowledge.service;

import java.io.InputStream;

/** 私有原件存储边界；对象键仅由服务端生成。 */
public interface OriginalFileStore {
    /** 原样保存文件，失败不得返回成功。 */
    void put(String key, byte[] content, String mediaType);

    /** 打开原件流，由调用方关闭。 */
    InputStream open(String key);

    /** 幂等删除指定对象。 */
    void delete(String key);
}
