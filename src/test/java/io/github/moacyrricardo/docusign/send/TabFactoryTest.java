package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.model.Tabs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TabFactoryTest {

    private final TabFactory factory = new TabFactory();

    @Test
    void signatureLandsInSignHereWithDefaults() {
        Tabs tabs = new Tabs();
        factory.applyTo(tabs, new AnchorSpec("_sig_363_", TabType.SIGNATURE, "a@b.com"));

        assertEquals(1, tabs.getSignHereTabs().size());
        var tab = tabs.getSignHereTabs().get(0);
        assertEquals("_sig_363_", tab.getAnchorString());
        assertEquals("pixels", tab.getAnchorUnits());
        assertEquals("0", tab.getAnchorXOffset());
        assertEquals("0", tab.getAnchorYOffset());
        assertEquals("false", tab.getAnchorIgnoreIfNotPresent());
        assertNull(tabs.getInitialHereTabs());
    }

    @Test
    void initialsDateTextLandInCorrectLists() {
        Tabs tabs = new Tabs();
        factory.applyTo(tabs, new AnchorSpec("_i_", TabType.INITIALS, "a@b.com"));
        factory.applyTo(tabs, new AnchorSpec("_d_", TabType.DATE, "a@b.com"));
        factory.applyTo(tabs, new AnchorSpec("_t_", TabType.TEXT, "a@b.com"));

        assertEquals(1, tabs.getInitialHereTabs().size());
        assertEquals(1, tabs.getDateSignedTabs().size());
        assertEquals(1, tabs.getTextTabs().size());
        assertEquals("true", tabs.getTextTabs().get(0).getRequired());
    }

    @Test
    void multipleSameTypeAccumulate() {
        Tabs tabs = new Tabs();
        factory.applyTo(tabs, new AnchorSpec("_a_", TabType.SIGNATURE, "a@b.com"));
        factory.applyTo(tabs, new AnchorSpec("_b_", TabType.SIGNATURE, "a@b.com"));
        assertEquals(2, tabs.getSignHereTabs().size());
    }
}
