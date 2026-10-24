#!/usr/bin/env python3
"""真实 Android UI 截图验收；不生成设计还原图。"""
import json, re, time, xml.etree.ElementTree as ET
from device_regression import shell, screenshot, dump, OUT, PKG

def click_id(node_id):
    data = dump('ui_current')
    root = ET.fromstring(data[data.index('<?xml'):])
    for node in root.iter('node'):
        if node.attrib.get('resource-id') == PKG + ':id/' + node_id:
            nums = list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
            if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
                shell('input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2))
                time.sleep(3);return
    raise RuntimeError('visible node missing: '+node_id)

def home():
    shell('am','start','--activity-clear-top','--activity-single-top','-n',PKG+'/com.agentruntime.ui.MainActivity');time.sleep(3)

def main():
    outcomes=[]
    home(); screenshot('main_portrait'); data=dump('main_portrait')
    r=ET.fromstring(data[data.index('<?xml'):]); matches=[n for n in r.iter('node') if n.attrib.get('resource-id','').endswith(':id/cb_auto_confirm')]
    outcomes.append({'case':'auto_confirm_default','passed':bool(matches) and matches[0].attrib.get('checked')=='false'})
    for key,name in [('btn_settings_top','settings'),('btn_skills','skills'),('btn_console','console')]:
        home();click_id(key);screenshot(name);dump(name)
        outcomes.append({'case':name,'screenshot':name+'.png'})
    home();shell('settings','put','system','accelerometer_rotation','0');shell('settings','put','system','user_rotation','1');time.sleep(5)
    screenshot('main_landscape');dump('main_landscape');outcomes.append({'case':'landscape','screenshot':'main_landscape.png'})
    shell('settings','put','system','user_rotation','0');time.sleep(3)
    home();click_id('chip_overlay');screenshot('overlay_bubble');dump('overlay_bubble')
    outcomes.append({'case':'overlay_bubble','screenshot':'overlay_bubble.png'})
    (OUT/'ui_checks.json').write_text(json.dumps(outcomes,ensure_ascii=False,indent=2))
    print(json.dumps(outcomes,ensure_ascii=False))

if __name__=='__main__':main()
