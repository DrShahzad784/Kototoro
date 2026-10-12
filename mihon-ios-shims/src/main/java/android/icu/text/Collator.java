package android.icu.text;

import java.util.Comparator;
import java.util.Locale;

/** Locale collation using the embedded JDK's locale data; ICU-specific tailoring is not claimed. */
public class Collator implements Comparator<Object>, Cloneable {
    public static final int PRIMARY = 0,
            SECONDARY = 1,
            TERTIARY = 2,
            QUATERNARY = 3,
            IDENTICAL = 15;
    public static final int NO_DECOMPOSITION = 16,
            CANONICAL_DECOMPOSITION = 17,
            FULL_DECOMPOSITION = 15;
    protected final java.text.Collator delegate;
    private boolean frozen;

    protected Collator(java.text.Collator delegate) {
        this.delegate = delegate;
    }

    public static Collator getInstance() {
        return getInstance(Locale.getDefault());
    }

    public static Collator getInstance(Locale locale) {
        java.text.Collator c = java.text.Collator.getInstance(locale);
        return c instanceof java.text.RuleBasedCollator
                ? new RuleBasedCollator((java.text.RuleBasedCollator) c)
                : new Collator(c);
    }

    public void setStrength(int strength) {
        writable();
        delegate.setStrength(strength == IDENTICAL ? java.text.Collator.IDENTICAL : strength);
    }

    public int getStrength() {
        int strength = delegate.getStrength();
        return strength == java.text.Collator.IDENTICAL ? IDENTICAL : strength;
    }

    public void setDecomposition(int mode) {
        writable();
        if (mode != NO_DECOMPOSITION
                && mode != CANONICAL_DECOMPOSITION
                && mode != FULL_DECOMPOSITION)
            throw new IllegalArgumentException("Unsupported decomposition mode");
        delegate.setDecomposition(
                mode == NO_DECOMPOSITION
                        ? java.text.Collator.NO_DECOMPOSITION
                        : mode == FULL_DECOMPOSITION
                                ? java.text.Collator.FULL_DECOMPOSITION
                                : java.text.Collator.CANONICAL_DECOMPOSITION);
    }

    public int getDecomposition() {
        int mode = delegate.getDecomposition();
        return mode == java.text.Collator.NO_DECOMPOSITION
                ? NO_DECOMPOSITION
                : mode == java.text.Collator.FULL_DECOMPOSITION
                        ? FULL_DECOMPOSITION
                        : CANONICAL_DECOMPOSITION;
    }

    public int compare(String a, String b) {
        return delegate.compare(a, b);
    }

    @Override
    public int compare(Object a, Object b) {
        return compare((String) a, (String) b);
    }

    public boolean equals(String a, String b) {
        return compare(a, b) == 0;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public Collator freeze() {
        frozen = true;
        return this;
    }

    protected void writable() {
        if (frozen) throw new UnsupportedOperationException("Collator is frozen");
    }

    public Collator cloneAsThawed() {
        return new Collator((java.text.Collator) delegate.clone());
    }

    @Override
    public Object clone() {
        Collator copy = cloneAsThawed();
        if (frozen) copy.freeze();
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Collator && delegate.equals(((Collator) other).delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }
}
