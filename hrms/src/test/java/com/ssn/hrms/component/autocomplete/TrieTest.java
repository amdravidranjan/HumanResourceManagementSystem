package com.ssn.hrms.component.autocomplete;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TrieTest {

    @Test
    void returnsTopKInLexicographicOrder() {
        Trie<String> t = new Trie<>(3);
        t.insert("Ravi Kumar", "1", "Ravi Kumar");
        t.insert("Rahul Sharma", "2", "Rahul Sharma");
        t.insert("Ramesh Iyer", "3", "Ramesh Iyer");
        t.insert("Rani Das", "4", "Rani Das");
        t.insert("Priya", "5", "Priya");
        assertThat(t.suggest("ra")).extracting(Trie.Entry::id).containsExactly("2", "3", "4");
        assertThat(t.suggest("rav")).extracting(Trie.Entry::id).containsExactly("1");
        assertThat(t.suggest("zz")).isEmpty();
    }

    @Test
    void normalisesAndIgnoresBlank() {
        Trie<String> t = new Trie<>(10);
        t.insert("  Anita   DESAI ", "1", "x");
        t.insert("   ", "2", "x");
        assertThat(t.suggest("ANITA d")).extracting(Trie.Entry::id).containsExactly("1");
        assertThat(t.suggest("")).isEmpty();
        assertThat(t.suggest("   ")).isEmpty();
        assertThat(t.suggest(".*(")).isEmpty();
        assertThat(t.suggest(null)).isEmpty();
    }

    @Test
    void removeLetsTheNextCandidateIn() {
        Trie<String> t = new Trie<>(2);
        t.insert("arun", "1", "a");
        t.insert("arjun", "2", "b");
        t.insert("arvind", "3", "c");
        assertThat(t.suggest("ar")).extracting(Trie.Entry::id).containsExactly("2", "1");
        t.remove("arjun", "2");
        assertThat(t.suggest("ar")).extracting(Trie.Entry::id).containsExactly("1", "3");
    }

    @Test
    void deduplicatesSameIdUnderDifferentTerms() {
        Trie<String> t = new Trie<>(5);
        t.insert("engineering", "1", "Ravi");
        t.insert("engineer", "1", "Ravi");
        t.insert("english", "2", "Meena");
        assertThat(t.suggest("eng")).extracting(Trie.Entry::id).containsExactly("1", "2");
    }

    @Test
    void bulkInsertMatchesBruteForceTopK() {
        Random rnd = new Random(42);
        String[] syll = {"ra", "vi", "an", "ka", "ma", "sh", "pr", "iy", "de", "su", "ni", "ta"};
        List<Trie.Entry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 2 + rnd.nextInt(3);
            for (int j = 0; j < n; j++) {
                sb.append(syll[rnd.nextInt(syll.length)]);
            }
            entries.add(new Trie.Entry<>(sb.toString(), String.valueOf(i), sb.toString()));
        }
        Trie<String> t = new Trie<>(10);
        t.insertAll(entries);
        for (String prefix : List.of("r", "ra", "vi", "sh", "kama", "ni")) {
            List<String> truth = entries.stream()
                    .filter(e -> e.term().startsWith(prefix))
                    .sorted(Comparator.comparing(Trie.Entry<String>::term).thenComparing(Trie.Entry::id))
                    .map(Trie.Entry::id).limit(10).toList();
            assertThat(t.suggest(prefix)).extracting(Trie.Entry::id).containsExactlyElementsOf(truth);
        }
        assertThat(t.size()).isEqualTo(5000);
    }
}
