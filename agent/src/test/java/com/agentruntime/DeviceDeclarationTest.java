package com.agentruntime;

import org.junit.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.assertEquals;

/** 声明层防回归；系统行为仍以真实设备测试为准。 */
public class DeviceDeclarationTest {
    private Element serviceDeclaration() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File("src/main/res/xml/accessibility_service_config.xml")).getDocumentElement();
    }

    @Test public void screenshotCapabilityIsDeclared() throws Exception {
        assertEquals("true", serviceDeclaration().getAttributeNS("http://schemas.android.com/apk/res/android", "canTakeScreenshot"));
    }

    @Test public void gestureCapabilityIsDeclared() throws Exception {
        assertEquals("true", serviceDeclaration().getAttributeNS("http://schemas.android.com/apk/res/android", "canPerformGestures"));
    }
}
