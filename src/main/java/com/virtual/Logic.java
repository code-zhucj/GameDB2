package com.virtual;

import java.util.concurrent.Future;

public interface Logic {

    enum State {
        SUCCESS,
        EXCEPTION,
        RETRY,
    }

    default Future<?> submit() {
        return TransactionImpl.submit(this);
    }

    /**
     * 逻辑核心处理方法
     *
     */
    State process() throws Exception;
}
