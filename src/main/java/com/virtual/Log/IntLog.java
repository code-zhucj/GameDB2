package com.virtual.Log;

import java.util.function.Consumer;

public class IntLog extends SimpleLog<Integer> {
    public IntLog(Integer integer, Consumer<Integer> consumer) {
        super(integer, consumer);
    }
}
