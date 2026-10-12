package android.icu.text;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Locale;

/**
 * Collation-aware substring search with optional boundary constraints. UTF-16 offsets are
 * preserved.
 */
public final class StringSearch extends SearchIterator {
    private String pattern;
    private RuleBasedCollator collator;
    private String text;
    private int begin;

    public StringSearch(
            String pattern,
            CharacterIterator target,
            RuleBasedCollator collator,
            BreakIterator boundary) {
        super(target, boundary);
        setPattern(pattern);
        this.collator = collator;
        snapshot();
    }

    public StringSearch(String pattern, CharacterIterator target, RuleBasedCollator collator) {
        this(pattern, target, collator, null);
    }

    public StringSearch(String pattern, CharacterIterator target, Locale locale) {
        this(pattern, target, (RuleBasedCollator) Collator.getInstance(locale));
    }

    public StringSearch(String pattern, String target) {
        this(pattern, new StringCharacterIterator(target), Locale.getDefault());
    }

    public RuleBasedCollator getCollator() {
        return collator;
    }

    public void setCollator(RuleBasedCollator collator) {
        this.collator = java.util.Objects.requireNonNull(collator);
        reset();
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("Pattern is empty");
        this.pattern = pattern;
    }

    private void snapshot() {
        CharacterIterator it = (CharacterIterator) targetText.clone();
        begin = it.getBeginIndex();
        StringBuilder out = new StringBuilder();
        for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) out.append(c);
        text = out.toString();
        if (breakIterator != null) breakIterator.setText(targetText);
    }

    @Override
    public void setTarget(CharacterIterator target) {
        super.setTarget(target);
        snapshot();
    }

    @Override
    public int getIndex() {
        return targetText.getIndex();
    }

    private boolean at(int start) {
        if (Character.isLowSurrogate(text.charAt(start))
                || Character.isWhitespace(text.charAt(start))) return false;
        if (breakIterator != null && !breakIterator.isBoundary(begin + start)) return false;
        // Collation can change the number of code units (e.g. combining accents), so compare
        // candidate ranges.
        for (int end = start + 1; end <= text.length(); end++) {
            if (collator.compare(pattern, text.substring(start, end)) == 0
                    && (breakIterator == null || breakIterator.isBoundary(begin + end))) {
                matchStart = begin + start;
                matchLength = end - start;
                targetText.setIndex(matchStart);
                return true;
            }
        }
        return false;
    }

    @Override
    protected int handleNext(int index) {
        for (int i = Math.max(0, index - begin); i < text.length(); i++)
            if (at(i)) return matchStart;
        matchStart = DONE;
        matchLength = 0;
        targetText.setIndex(targetText.getEndIndex());
        return DONE;
    }

    @Override
    protected int handlePrevious(int index) {
        for (int i = Math.min(text.length() - 1, index - begin - 1); i >= 0; i--)
            if (at(i)) return matchStart;
        matchStart = DONE;
        matchLength = 0;
        targetText.setIndex(begin);
        return DONE;
    }
}
