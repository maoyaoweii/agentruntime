package com.agentruntime.vision;

import com.agentruntime.state.UiNode;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class ScreenOcrBoundsTest {
    private final ScreenFrame frame=new ScreenFrame(1000,2000,500,1000,new byte[]{1},"image/jpeg",0,0);
    @Test public void mapsAndClampsTheAppBoundsToTheScreenshot() {
        assertArrayEquals(new int[]{50,100,450,850},OnDeviceScreenOcr.cropBounds(node(100,200,1100,1900),frame));
    }
    @Test public void missingBoundsNeverDefaultToTheWholeDisplay() {
        assertNull(OnDeviceScreenOcr.cropBounds(node(0,0,0,0),frame));
        assertNull(OnDeviceScreenOcr.cropBounds(null,frame));
    }
    private static UiNode node(int left,int top,int right,int bottom) {
        return new UiNode("root","View",null,null,null,false,true,true,false,false,false,false,
                left,top,right,bottom,Collections.emptyList());
    }
}
