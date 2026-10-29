package com.agentruntime;

import com.agentruntime.dsl.ActionSpec;
import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.assertEquals;

public class LocaleProtocolTest {
    @Test public void riskAndActionNamesAreIndependentOfDeviceLanguage() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(ActionSpec.RiskLevel.HIGH, ActionSpec.RiskLevel.fromString("high"));
            assertEquals(ActionSpec.ActionType.INPUT_TEXT, ActionSpec.ActionType.fromString("INPUT_TEXT"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
