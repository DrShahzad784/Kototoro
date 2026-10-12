package android.icu.text;

import java.text.CharacterIterator;

public abstract class SearchIterator {
    public static final int DONE = -1;
    protected CharacterIterator targetText;
    protected BreakIterator breakIterator;
    protected int matchLength;
    protected int matchStart = DONE;
    private boolean overlapping;

    protected SearchIterator(CharacterIterator target, BreakIterator boundary) {
        targetText = target;
        breakIterator = boundary;
    }

    public void setIndex(int index) {
        if (index < targetText.getBeginIndex() || index > targetText.getEndIndex())
            throw new IndexOutOfBoundsException();
        targetText.setIndex(index);
        matchStart = DONE;
        matchLength = 0;
    }

    public abstract int getIndex();

    public void setOverlapping(boolean value) {
        overlapping = value;
    }

    public boolean isOverlapping() {
        return overlapping;
    }

    public void setBreakIterator(BreakIterator boundary) {
        breakIterator = boundary;
    }

    public BreakIterator getBreakIterator() {
        return breakIterator;
    }

    public void setTarget(CharacterIterator target) {
        targetText = target;
        reset();
    }

    public CharacterIterator getTarget() {
        return targetText;
    }

    public int getMatchStart() {
        return matchStart;
    }

    public int getMatchLength() {
        return matchLength;
    }

    protected void setMatchLength(int length) {
        matchLength = length;
    }

    public String getMatchedText() {
        if (matchStart == DONE) return null;
        CharacterIterator it = (CharacterIterator) targetText.clone();
        StringBuilder out = new StringBuilder();
        for (int i = matchStart; i < matchStart + matchLength; i++) out.append(it.setIndex(i));
        return out.toString();
    }

    public int next() {
        return handleNext(
                matchStart == DONE
                        ? getIndex()
                        : matchStart + (overlapping ? 1 : Math.max(1, matchLength)));
    }

    public int previous() {
        return handlePrevious(matchStart == DONE ? getIndex() : matchStart);
    }

    public void reset() {
        targetText.first();
        matchStart = DONE;
        matchLength = 0;
    }

    public final int first() {
        return handleNext(targetText.getBeginIndex());
    }

    public final int last() {
        return handlePrevious(targetText.getEndIndex());
    }

    public final int following(int index) {
        setIndex(index);
        return handleNext(index);
    }

    public final int preceding(int index) {
        setIndex(index);
        return handlePrevious(index);
    }

    protected abstract int handleNext(int index);

    protected abstract int handlePrevious(int index);
}
