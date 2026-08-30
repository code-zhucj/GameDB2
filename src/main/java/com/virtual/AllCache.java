package com.virtual;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author zhuchuanji
 * @Description 全缓存,直接就是个ConcurrentHashMap无需额外处理
 * @Create: 2026/8/17 1:42
 */
class AllCache<E extends TableDefine> extends ConcurrentHashMap<Comparable<?>, Record<E>> {
}
