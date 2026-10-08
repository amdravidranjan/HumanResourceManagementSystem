package com.ssn.hrms.component.autocomplete;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prefix trie where every node caches its top-k completions, so a suggestion lookup is
 * O(length of prefix) regardless of how many terms are stored.
 * Updates recompute the cached lists along the inserted path only.
 */
public class Trie<T> {

    public record Entry<T>(String term, String id, T value) {
    }

    private static final class Node<T> {
        final TreeMap<Character, Node<T>> children = new TreeMap<>();
        final List<Entry<T>> terminal = new ArrayList<>(1);
        List<Entry<T>> top = List.of();
    }

    private final int k;
    private final Comparator<Entry<T>> order = Comparator.comparing((Entry<T> e) -> e.term()).thenComparing(Entry::id);
    private final Node<T> root = new Node<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private int size;

    public Trie(int k) {
        this.k = k;
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public void insert(String term, String id, T value) {
        String t = normalize(term);
        if (t.isEmpty() || id == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            List<Node<T>> path = pathFor(t, true);
            addTerminal(path.get(path.size() - 1), new Entry<>(t, id, value));
            for (int i = path.size() - 1; i >= 0; i--) {
                recompute(path.get(i));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void insertAll(Collection<Entry<T>> entries) {
        lock.writeLock().lock();
        try {
            for (Entry<T> e : entries) {
                String t = normalize(e.term());
                if (t.isEmpty() || e.id() == null) {
                    continue;
                }
                List<Node<T>> path = pathFor(t, true);
                addTerminal(path.get(path.size() - 1), new Entry<>(t, e.id(), e.value()));
            }
            recomputeAll();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String term, String id) {
        String t = normalize(term);
        if (t.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            List<Node<T>> path = pathFor(t, false);
            if (path == null) {
                return;
            }
            if (path.get(path.size() - 1).terminal.removeIf(e -> e.id().equals(id))) {
                size--;
                for (int i = path.size() - 1; i >= 0; i--) {
                    recompute(path.get(i));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Entry<T>> suggest(String prefix) {
        String p = normalize(prefix);
        if (p.isEmpty()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            Node<T> n = root;
            for (char c : p.toCharArray()) {
                n = n.children.get(c);
                if (n == null) {
                    return List.of();
                }
            }
            return n.top;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<Node<T>> pathFor(String t, boolean create) {
        List<Node<T>> path = new ArrayList<>(t.length() + 1);
        Node<T> n = root;
        path.add(n);
        for (char c : t.toCharArray()) {
            Node<T> next = n.children.get(c);
            if (next == null) {
                if (!create) {
                    return null;
                }
                next = new Node<>();
                n.children.put(c, next);
            }
            n = next;
            path.add(n);
        }
        return path;
    }

    private void addTerminal(Node<T> n, Entry<T> e) {
        if (!n.terminal.removeIf(x -> x.id().equals(e.id()))) {
            size++;
        }
        n.terminal.add(e);
    }

    private void recompute(Node<T> n) {
        List<Entry<T>> candidates = new ArrayList<>(n.terminal);
        for (Node<T> child : n.children.values()) {
            candidates.addAll(child.top);
        }
        candidates.sort(order);
        List<Entry<T>> out = new ArrayList<>(Math.min(k, candidates.size()));
        Set<String> seen = new HashSet<>();
        for (Entry<T> e : candidates) {
            if (seen.add(e.id())) {
                out.add(e);
                if (out.size() == k) {
                    break;
                }
            }
        }
        n.top = List.copyOf(out);
    }

    /** Iterative post-order recompute of every node (used after bulk loads). */
    private void recomputeAll() {
        Deque<Node<T>> stack = new ArrayDeque<>();
        List<Node<T>> order = new ArrayList<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node<T> n = stack.pop();
            order.add(n);
            n.children.values().forEach(stack::push);
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            recompute(order.get(i));
        }
    }
}
