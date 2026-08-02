package com.virtual;

import com.virtual.api.TableProvider;

import java.util.HashMap;
import java.util.Map;

public class Tables {

    /**
     * 创建 _table
     */
    public static <T extends TableDefine> T create(Class<T> entityClass) {
        return TablesManager.INSTANCE.tableProvider.create(entityClass);
    }

    public static Class<? extends TableDefine> getProxyClass(Class<? extends TableDefine> entityClass) {
        return TablesManager.INSTANCE.tableProvider.getProxyClass(entityClass);
    }

    public static <T extends TableDefine> Table<T> getTable(Class<T> tableClass) {
        return TablesManager.INSTANCE.getTable(tableClass);
    }

    public static void init(TableProvider tableProvider) {
        TablesManager.INSTANCE.initTable(tableProvider);
    }

    private enum TablesManager {
        INSTANCE;
        private final Map<Class<?>, Table<?>> tables = new HashMap<>();
        private TableProvider tableProvider;

        @SuppressWarnings("unchecked")
        public <T extends TableDefine> Table<T> getTable(Class<T> tableClass) {
            return (Table<T>) tables.get(tableClass);
        }

        public void initTable(TableProvider tableProvider) {
            this.tableProvider = tableProvider;
            for (Class<? extends TableDefine> tableClass : tableProvider.allTableClass()) {
                Table<?> table = new Table<>(tableClass);
                tables.put(tableClass, table);
                table.loadFromDB();
            }
        }
    }
}
