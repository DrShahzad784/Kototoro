package android.icu.text;

import java.text.CharacterIterator;
import java.util.Locale;

/** ICU's common boundary iterator API backed by the JDK's Unicode boundary implementation. */
public class BreakIterator implements Cloneable {
    public static final int DONE = -1;
    private final java.text.BreakIterator delegate;

    private BreakIterator(java.text.BreakIterator delegate) {
        this.delegate = delegate;
    }

    public static BreakIterator getWordInstance() {
        return getWordInstance(Locale.getDefault());
    }

    public static BreakIterator getWordInstance(Locale locale) {
        return new BreakIterator(java.text.BreakIterator.getWordInstance(locale));
    }

    public static BreakIterator getLineInstance() {
        return getLineInstance(Locale.getDefault());
    }

    public static BreakIterator getLineInstance(Locale locale) {
        return new BreakIterator(java.text.BreakIterator.getLineInstance(locale));
    }

    public static BreakIterator getCharacterInstance() {
        return getCharacterInstance(Locale.getDefault());
    }

    public static BreakIterator getCharacterInstance(Locale locale) {
        return new BreakIterator(java.text.BreakIterator.getCharacterInstance(locale));
    }

    public static BreakIterator getSentenceInstance() {
        return getSentenceInstance(Locale.getDefault());
    }

    public static BreakIterator getSentenceInstance(Locale locale) {
        return new BreakIterator(java.text.BreakIterator.getSentenceInstance(locale));
    }

    public int first() {
        return delegate.first();
    }

    public int last() {
        return delegate.last();
    }

    public int next() {
        return delegate.next();
    }

    public int next(int n) {
        return delegate.next(n);
    }

    public int previous() {
        return delegate.previous();
    }

    public int following(int offset) {
        return delegate.following(offset);
    }

    public int preceding(int offset) {
        return delegate.preceding(offset);
    }

    public boolean isBoundary(int offset) {
        return delegate.isBoundary(offset);
    }

    public int current() {
        return delegate.current();
    }

    public CharacterIterator getText() {
        return delegate.getText();
    }

    public void setText(String text) {
        delegate.setText(text);
    }

    public void setText(CharSequence text) {
        delegate.setText(text.toString());
    }

    public void setText(CharacterIterator text) {
        delegate.setText(text);
    }

    @Override
    public Object clone() {
        return new BreakIterator((java.text.BreakIterator) delegate.clone());
    }
}
