"""生成 V1.2 的视觉资源：colors / styles / drawable / 图标。

集中生成的原因：这些 XML 都很短但数量多，手写容易在圆角、描边、色值上漂移。
"""
import io
import os

RES = r'E:/agentruntime/agent/src/main/res'


def write(rel, content):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(content.strip() + '\n')
    print('  ', rel)


COLORS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 主色：靛蓝。选它是因为这个 App 的性质是"工具"，不是"玩具" -->
    <color name="brand">#4F5BD5</color>
    <color name="brand_dark">#3B45A8</color>
    <color name="brand_soft">#E9EBFB</color>
    <color name="brand_line">#C9CEF5</color>

    <color name="bg">#F4F5F9</color>
    <color name="surface">#FFFFFF</color>
    <color name="surface_alt">#FAFBFD</color>

    <color name="text_primary">#191B23</color>
    <color name="text_secondary">#666D82</color>
    <color name="text_tertiary">#98A0B5</color>
    <color name="text_on_brand">#FFFFFF</color>

    <color name="divider">#EAECF3</color>
    <color name="stroke">#E2E5EF</color>

    <color name="ok">#0F9D58</color>
    <color name="ok_bg">#E5F5EC</color>
    <color name="warn">#B76E00</color>
    <color name="warn_bg">#FFF3DF</color>
    <color name="danger">#D3453F</color>
    <color name="danger_bg">#FCEBE9</color>
    <color name="neutral_bg">#EDEFF6</color>

    <!-- 控制台日志区：深色，长看不刺眼 -->
    <color name="console_bg">#1C1E26</color>
    <color name="console_text">#C3CADA</color>
    <color name="console_dim">#7A8398</color>
    <color name="console_ok">#6FD08C</color>
    <color name="console_warn">#E8B45C</color>
    <color name="console_err">#F07A72</color>
</resources>
"""

STYLES = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:colorPrimary">@color/brand</item>
        <item name="android:colorPrimaryDark">@color/brand_dark</item>
        <item name="android:colorAccent">@color/brand</item>
        <item name="android:windowBackground">@color/bg</item>
        <item name="android:statusBarColor">@color/brand</item>
        <item name="android:navigationBarColor">@color/bg</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>

    <style name="OverlayTheme" parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:colorAccent">@color/brand</item>
    </style>

    <!-- 文字层级 -->
    <style name="T.Title">
        <item name="android:textSize">20sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="T.Section">
        <item name="android:textSize">13sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:textColor">@color/text_secondary</item>
        <item name="android:letterSpacing">0.04</item>
    </style>

    <style name="T.Body">
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:lineSpacingExtra">3dp</item>
    </style>

    <style name="T.Caption">
        <item name="android:textSize">12sp</item>
        <item name="android:textColor">@color/text_tertiary</item>
    </style>

    <style name="T.Value">
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:fontFamily">monospace</item>
    </style>

    <!-- 卡片 -->
    <style name="Card">
        <item name="android:background">@drawable/bg_card</item>
        <item name="android:padding">16dp</item>
        <item name="android:layout_marginBottom">12dp</item>
    </style>

    <!-- 按钮 -->
    <style name="Btn.Primary">
        <item name="android:background">@drawable/bg_btn_primary</item>
        <item name="android:textColor">@color/text_on_brand</item>
        <item name="android:textSize">15sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:gravity">center</item>
        <item name="android:layout_height">48dp</item>
        <item name="android:stateListAnimator">@null</item>
        <item name="android:textAllCaps">false</item>
    </style>

    <style name="Btn.Ghost">
        <item name="android:background">@drawable/bg_btn_ghost</item>
        <item name="android:textColor">@color/brand</item>
        <item name="android:textSize">14sp</item>
        <item name="android:gravity">center</item>
        <item name="android:layout_height">44dp</item>
        <item name="android:stateListAnimator">@null</item>
        <item name="android:textAllCaps">false</item>
    </style>

    <!-- 输入框 -->
    <style name="Input">
        <item name="android:background">@drawable/bg_input</item>
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textColorHint">@color/text_tertiary</item>
        <item name="android:padding">12dp</item>
        <item name="android:minHeight">48dp</item>
        <item name="android:textCursorDrawable">@null</item>
    </style>
</resources>
"""

DRAWABLES = {
    'drawable/bg_card.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface"/>
    <corners android:radius="16dp"/>
    <stroke android:width="1dp" android:color="@color/stroke"/>
</shape>""",

    'drawable/bg_card_soft.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_alt"/>
    <corners android:radius="12dp"/>
    <stroke android:width="1dp" android:color="@color/divider"/>
</shape>""",

    'drawable/bg_btn_primary.xml': """<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="#33FFFFFF">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/brand"/>
            <corners android:radius="12dp"/>
        </shape>
    </item>
</ripple>""",

    'drawable/bg_btn_ghost.xml': """<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="#1A4F5BD5">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent"/>
            <corners android:radius="12dp"/>
            <stroke android:width="1dp" android:color="@color/brand_line"/>
        </shape>
    </item>
</ripple>""",

    'drawable/bg_btn_danger.xml': """<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="#1AD3453F">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent"/>
            <corners android:radius="12dp"/>
            <stroke android:width="1dp" android:color="#F3C9C6"/>
        </shape>
    </item>
</ripple>""",

    'drawable/bg_input.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_alt"/>
    <corners android:radius="12dp"/>
    <stroke android:width="1dp" android:color="@color/stroke"/>
</shape>""",

    'drawable/bg_badge_ok.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/ok_bg"/>
    <corners android:radius="100dp"/>
</shape>""",

    'drawable/bg_badge_warn.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/warn_bg"/>
    <corners android:radius="100dp"/>
</shape>""",

    'drawable/bg_badge_danger.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/danger_bg"/>
    <corners android:radius="100dp"/>
</shape>""",

    'drawable/bg_badge_neutral.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/neutral_bg"/>
    <corners android:radius="100dp"/>
</shape>""",

    'drawable/bg_chip.xml': """<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="#1A4F5BD5">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/surface"/>
            <corners android:radius="100dp"/>
            <stroke android:width="1dp" android:color="@color/stroke"/>
        </shape>
    </item>
</ripple>""",

    'drawable/bg_console.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/console_bg"/>
    <corners android:radius="12dp"/>
</shape>""",

    'drawable/bg_overlay_panel.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface"/>
    <corners android:radius="18dp"/>
    <stroke android:width="1dp" android:color="@color/stroke"/>
</shape>""",

    'drawable/bg_bubble.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/brand"/>
    <stroke android:width="2dp" android:color="#33FFFFFF"/>
</shape>""",

    'drawable/bg_icon_square.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/brand_soft"/>
    <corners android:radius="12dp"/>
</shape>""",

    'drawable/bg_divider.xml': """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/divider"/>
</shape>""",

    # ------- 矢量图标 -------
    'drawable/ic_bot.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,2.5a1.2,1.2 0 0,1 1.2,1.2v1.1h2.6A3.2,3.2 0 0,1 19,8v8a3.2,3.2 0 0,1 -3.2,3.2H8.2A3.2,3.2 0 0,1 5,16V8a3.2,3.2 0 0,1 3.2,-3.2h2.6V3.7A1.2,1.2 0 0,1 12,2.5zM8.5,9.3a1.3,1.3 0 1,0 0,2.6a1.3,1.3 0 0,0 0,-2.6zM15.5,9.3a1.3,1.3 0 1,0 0,2.6a1.3,1.3 0 0,0 0,-2.6zM9,15.2a1,1 0 0,0 0,2h6a1,1 0 0,0 0,-2z"/>
</vector>""",

    'drawable/ic_settings.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#666D82"
        android:pathData="M4,6.5h16a1,1 0 0,1 0,2H4a1,1 0 0,1 0,-2zM4,15.5h16a1,1 0 0,1 0,2H4a1,1 0 0,1 0,-2z"/>
    <path android:fillColor="#4F5BD5"
        android:pathData="M9,4.2a2.8,2.8 0 1,1 0,5.6a2.8,2.8 0 0,1 0,-5.6zM15,13.2a2.8,2.8 0 1,1 0,5.6a2.8,2.8 0 0,1 0,-5.6z"/>
</vector>""",

    'drawable/ic_play.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF" android:pathData="M8,5.5l11,6.5l-11,6.5z"/>
</vector>""",

    'drawable/ic_close.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="18dp" android:height="18dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#666D82" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:pathData="M6,6L18,18M18,6L6,18"/>
</vector>""",

    'drawable/ic_back.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="22dp" android:height="22dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M15,5L8,12L15,19"/>
</vector>""",

    'drawable/ic_chevron.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="18dp" android:height="18dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#98A0B5" android:strokeWidth="2"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M9,5L16,12L9,19"/>
</vector>""",

    'drawable/ic_launcher.xml': """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z" android:fillColor="#4F5BD5"/>
    <path android:fillColor="#FFFFFF"
        android:pathData="M54,26a5,5 0 0,1 5,5v4h11a13,13 0 0,1 13,13v22a13,13 0 0,1 -13,13H40a13,13 0 0,1 -13,-13V48a13,13 0 0,1 13,-13h11v-4a5,5 0 0,1 5,-5zM41,53a6,6 0 1,0 0,12a6,6 0 0,0 0,-12zM67,53a6,6 0 1,0 0,12a6,6 0 0,0 0,-12zM43,74a4,4 0 0,0 0,8h22a4,4 0 0,0 0,-8z"/>
</vector>""",
}

for rel, content in DRAWABLES.items():
    write(rel, content)

write('values/colors.xml', COLORS)
write('values/styles.xml', STYLES)
print('done')
