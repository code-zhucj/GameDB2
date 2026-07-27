package com.virtual.Log;

import java.util.function.Consumer;

public class StringLog extends SimpleLog<String> {


    public StringLog(String s, Consumer<String> consumer) {
        super(s, consumer);
    }
}
