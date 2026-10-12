package android.icu.text;

import java.text.ParseException;

public final class RuleBasedCollator extends Collator {
    public RuleBasedCollator(String rules) throws ParseException {
        super(new java.text.RuleBasedCollator(rules));
    }

    RuleBasedCollator(java.text.RuleBasedCollator delegate) {
        super(delegate);
    }

    public String getRules() {
        return ((java.text.RuleBasedCollator) delegate).getRules();
    }

    @Override
    public RuleBasedCollator cloneAsThawed() {
        return new RuleBasedCollator((java.text.RuleBasedCollator) delegate.clone());
    }

    @Override
    public RuleBasedCollator freeze() {
        super.freeze();
        return this;
    }
}
