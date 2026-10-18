package io.origcore.config;

/**
 * 設定檔內容不合法時丟出。
 *
 * <p>OrigCore 不會默默接受不合法或超出範圍的設定；一旦丟出此例外，
 * 呼叫端必須把訊息完整印到主控台，並停用需要該設定的功能。</p>
 */
public class OrigCoreConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public OrigCoreConfigException(final String message) {
        super(message);
    }

    public OrigCoreConfigException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
