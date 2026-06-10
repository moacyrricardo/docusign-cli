package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.model.DateSigned;
import com.docusign.esign.model.InitialHere;
import com.docusign.esign.model.SignHere;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds DocuSign anchor tabs from {@link AnchorSpec}s with hardcoded placement defaults (spec 005
 * §4 — no x/y/unit flags in v1) and appends each into the correct per-recipient {@link Tabs} list.
 * Extension is an enum constant + a {@code case} here + the matching {@code Tabs} list.
 */
public final class TabFactory {

    static final String ANCHOR_UNITS = "pixels";
    static final String ANCHOR_OFFSET = "0";
    static final String ANCHOR_IGNORE_IF_NOT_PRESENT = "false";
    static final String TEXT_REQUIRED = "true";

    /** Appends a tab for {@code spec} onto {@code tabs}, into the list matching {@code spec.type()}. */
    public void applyTo(Tabs tabs, AnchorSpec spec) {
        String anchor = spec.anchorString();
        switch (spec.type()) {
            case SIGNATURE -> {
                SignHere tab = new SignHere();
                tab.setAnchorString(anchor);
                tab.setAnchorUnits(ANCHOR_UNITS);
                tab.setAnchorXOffset(ANCHOR_OFFSET);
                tab.setAnchorYOffset(ANCHOR_OFFSET);
                tab.setAnchorIgnoreIfNotPresent(ANCHOR_IGNORE_IF_NOT_PRESENT);
                tabs.setSignHereTabs(append(tabs.getSignHereTabs(), tab));
            }
            case INITIALS -> {
                InitialHere tab = new InitialHere();
                tab.setAnchorString(anchor);
                tab.setAnchorUnits(ANCHOR_UNITS);
                tab.setAnchorXOffset(ANCHOR_OFFSET);
                tab.setAnchorYOffset(ANCHOR_OFFSET);
                tab.setAnchorIgnoreIfNotPresent(ANCHOR_IGNORE_IF_NOT_PRESENT);
                tabs.setInitialHereTabs(append(tabs.getInitialHereTabs(), tab));
            }
            case DATE -> {
                DateSigned tab = new DateSigned();
                tab.setAnchorString(anchor);
                tab.setAnchorUnits(ANCHOR_UNITS);
                tab.setAnchorXOffset(ANCHOR_OFFSET);
                tab.setAnchorYOffset(ANCHOR_OFFSET);
                tab.setAnchorIgnoreIfNotPresent(ANCHOR_IGNORE_IF_NOT_PRESENT);
                tabs.setDateSignedTabs(append(tabs.getDateSignedTabs(), tab));
            }
            case TEXT -> {
                Text tab = new Text();
                tab.setAnchorString(anchor);
                tab.setAnchorUnits(ANCHOR_UNITS);
                tab.setAnchorXOffset(ANCHOR_OFFSET);
                tab.setAnchorYOffset(ANCHOR_OFFSET);
                tab.setAnchorIgnoreIfNotPresent(ANCHOR_IGNORE_IF_NOT_PRESENT);
                tab.setRequired(TEXT_REQUIRED);
                tabs.setTextTabs(append(tabs.getTextTabs(), tab));
            }
        }
    }

    private static <T> List<T> append(List<T> existing, T value) {
        List<T> list = (existing != null) ? existing : new ArrayList<>();
        list.add(value);
        return list;
    }
}
