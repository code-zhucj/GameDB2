package com.virtual.Log;

import java.util.function.Consumer;

public class LongLog extends SimpleLog<Long> {

    public LongLog(Long aLong, Consumer<Long> consumer) {
        super(aLong, consumer);
    }
}
