package com.virtual.Log;

import com.virtual.Transaction;

public interface Log<V> extends Transaction {

    V getValue();
}
