package com.ssn.hrms.component.hashing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Consistent-hash ring over a 2^32 key space. Each member is placed at {@code virtualNodes}
 * positions (hash of "name#i"); a key belongs to the first member clockwise from hash(key).
 * Adding/removing a member only remaps the keys in the arcs that member gains/loses (~1/N).
 */
public class ConsistentHashRing<T> {

    private static final double RING_SIZE = 4294967296.0; // 2^32

    private final int virtualNodes;
    private final Function<T, String> nameOf;
    private final TreeMap<Long, T> ring = new TreeMap<>();
    private final Set<T> members = new LinkedHashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing(int virtualNodes, Function<T, String> nameOf) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1");
        }
        this.virtualNodes = virtualNodes;
        this.nameOf = nameOf;
    }

    public static long hash(String s) {
        return MurmurHash3.hashUnsigned(s);
    }

    public void add(T member) {
        lock.writeLock().lock();
        try {
            if (members.add(member)) {
                String name = nameOf.apply(member);
                for (int i = 0; i < virtualNodes; i++) {
                    ring.putIfAbsent(hash(name + "#" + i), member);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(T member) {
        lock.writeLock().lock();
        try {
            if (members.remove(member)) {
                String name = nameOf.apply(member);
                for (int i = 0; i < virtualNodes; i++) {
                    ring.remove(hash(name + "#" + i), member);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public T get(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("No members in ring");
            }
            Map.Entry<Long, T> e = ring.ceilingEntry(hash(key));
            return (e != null ? e : ring.firstEntry()).getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<T> members() {
        lock.readLock().lock();
        try {
            return new LinkedHashSet<>(members);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return members().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Fraction of the 2^32 key space owned by each member (sums to 1.0). */
    public Map<T, Double> ownership() {
        lock.readLock().lock();
        try {
            Map<T, Double> out = new LinkedHashMap<>();
            members.forEach(m -> out.put(m, 0.0));
            if (ring.isEmpty()) {
                return out;
            }
            long prev = ring.lastKey() - (1L << 32);
            for (Map.Entry<Long, T> e : ring.entrySet()) {
                out.merge(e.getValue(), (e.getKey() - prev) / RING_SIZE, Double::sum);
                prev = e.getKey();
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }
}
