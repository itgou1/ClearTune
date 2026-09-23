"""Render visual proposals for the Music Tag flow; does not change the Android app."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = Path(__file__).parent
ROOT = HERE.parents[1]
S = 2
W, H = 393, 852
BG = "#F5F3FF"
SURFACE = "#FFFBFF"
INK = "#1C1B1F"
MUTED = "#49454F"
PRIMARY = "#4965A1"
PRIMARY_PALE = "#DCE5FF"
TERTIARY = "#006782"
TERTIARY_PALE = "#B9EAFF"
OUTLINE = "#C7C9D4"
SOFT = "#F1EFFF"
RED = "#B14341"
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def f(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, int(size * S))


class Phone:
    def __init__(self):
        self.im = Image.new("RGB", (W * S, H * S), BG)
        self.d = ImageDraw.Draw(self.im)

    def box(self, x, y, w, h, color, radius=0, border=None, stroke=1):
        self.d.rounded_rectangle((x*S, y*S, (x+w)*S, (y+h)*S),
                                 radius=radius*S, fill=color,
                                 outline=border, width=stroke*S)

    def line(self, points, color=INK, width=2):
        self.d.line([(int(x*S), int(y*S)) for x,y in points], fill=color, width=width*S, joint="curve")

    def circle(self, x, y, r, color, outline=None, width=1):
        self.d.ellipse(((x-r)*S, (y-r)*S, (x+r)*S, (y+r)*S),
                       fill=color, outline=outline, width=width*S)

    def dim(self, top=42):
        shade=Image.new("RGBA",self.im.size,(0,0,0,0))
        ImageDraw.Draw(shade).rectangle((0,top*S,W*S,828*S),fill=(20,20,31,145))
        self.im=Image.alpha_composite(self.im.convert("RGBA"),shade).convert("RGB")
        self.d=ImageDraw.Draw(self.im)

    def text(self, x, y, value, size=14, color=INK, bold=False, max_w=None):
        font = f(size, bold)
        if max_w:
            while self.d.textlength(value, font=font) > max_w*S and len(value) > 2:
                value = value[:-2] + "…"
        self.d.text((x*S, y*S), value, font=font, fill=color)

    def center(self, x, y, value, size=14, color=INK, bold=False):
        font = f(size, bold)
        width = self.d.textlength(value, font=font) / S
        self.text(x-width/2, y, value, size, color, bold)

    def photo(self, x, y, size=64, alternate=False):
        if alternate:
            art = alternate_art(size*S)
        else:
            source = Image.open(ROOT / "app/build/reports/music-tag-ui/tag-editor-ux-live.png").convert("RGB")
            art = source.crop((53,324,242,513)).resize((size*S, size*S), Image.Resampling.LANCZOS)
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0,0,art.width-1,art.height-1), radius=12*S, fill=255)
        self.im.paste(art, (x*S,y*S), mask)

    def chevron(self, x, y, down=True, color=PRIMARY):
        if down: self.line([(x-5,y-2),(x,y+3),(x+5,y-2)], color, 2)
        else: self.line([(x-5,y+2),(x,y-3),(x+5,y+2)], color, 2)

    def footer(self):
        self.box(0, 828, 393, 24, "#2D2E34")
        self.box(139, 839, 115, 4, "#FFFFFF", 2)

    def chrome(self, title="音乐标签", gear=True):
        self.box(0,0,393,42,"#2D2E34")
        self.text(20,13,"10:44",11,"#FFFFFF")
        self.text(316,13,"5G",10,"#FFFFFF",True)
        for i, height in enumerate((4,6,8,10)):
            self.box(342+i*5,25-height,3,height,"#FFFFFF",1)
        self.box(365,15,18,9,"#FFFFFF",2)
        self.box(384,18,2,3,"#FFFFFF",1)
        self.line([(26,67),(18,75),(26,83)], INK, 2)
        self.line([(18,75),(33,75)], INK, 2)
        self.text(51,61,title,20,INK,True)
        if gear:
            for points in [((360,65),(360,69)),((360,83),(360,87)),((349,76),(353,76)),((367,76),(371,76)),
                           ((352,68),(355,71)),((365,81),(368,84)),((368,68),(365,71)),((355,81),(352,84))]:
                self.line(points,INK,3)
            self.circle(360,76,7,INK)
            self.circle(360,76,3,BG)

    def button(self, x,y,w,h,label,fill=PRIMARY,color="#FFFFFF",border=None,size=14):
        self.box(x,y,w,h,fill,22 if h>=40 else 13,border)
        self.center(x+w/2,y+(h-size)/2-2,label,size,color,True)

    def field(self, x,y,w,label,value,changed=False,original=None):
        self.box(x,y,w,60,SURFACE,12,TERTIARY if changed else OUTLINE,2 if changed else 1)
        self.text(x+14,y+7,label,11,TERTIARY if changed else MUTED)
        self.text(x+14,y+29,value,14,INK,False,w-28)
        if changed and original:
            self.text(x+2,y+65,"原值："+original,11,MUTED,max_w=w-4)

    def tabbar(self, active="edit"):
        self.box(18,236,357,42,"#EBE9F7",18)
        x = 21 if active=="edit" else 198
        self.box(x,239,174,36,SURFACE,16)
        self.center(108,248,"编辑标签",13,PRIMARY if active=="edit" else MUTED,active=="edit")
        self.center(286,248,"刮削匹配",13,PRIMARY if active=="scrape" else MUTED,active=="scrape")

    def song(self, draft=False, art_alt=False, saved=False):
        self.photo(18,116,70,art_alt)
        self.text(101,115,"放过今天的我吧",17,INK,True,max_w=274)
        self.text(101,142,"张星遥",13,MUTED)
        self.text(101,163,"夜色来信" if draft or saved else "张星遥的作品集",12,MUTED)
        self.box(18,201,102 if draft else 116,24,TERTIARY_PALE if draft else PRIMARY_PALE,12)
        self.text(30,204,"草稿 · 未保存" if draft else "原文件已读取",11,TERTIARY if draft else PRIMARY,True)
        self.text(334,204,"文件信息",10,PRIMARY)

    def save(self, changed=False):
        self.box(0,760,393,68,SURFACE)
        if changed:
            self.text(18,767,"已修改 2 项：专辑、封面",11,TERTIARY,True)
            self.button(18,786,357,38,"保存修改")
        else:
            self.circle(27,790,7,PRIMARY_PALE)
            self.text(43,780,"当前内容与原文件一致",12,MUTED)
            self.text(43,800,"修改字段后可保存",10,MUTED)
        self.footer()


def alternate_art(n):
    art = Image.new("RGB", (n,n), "#1B315A")
    px = art.load()
    for yy in range(n):
        for xx in range(n):
            t = yy/n
            px[xx,yy] = (int(20+35*t), int(42+22*t), int(80+65*t))
    dr=ImageDraw.Draw(art)
    dr.ellipse((n*.52,n*.14,n*.82,n*.44), fill="#F8DAB2")
    dr.polygon([(0,n*.79),(n*.37,n*.55),(n*.69,n*.76),(n,n*.52),(n,n),(0,n)],fill="#2C5479")
    dr.text((n*.16,n*.68),"夜色",font=f(max(12,n/S*.18),True),fill="#F9EEF9")
    return art


def screen_edit():
    p=Phone(); p.chrome(); p.song(); p.tabbar("edit")
    p.text(18,296,"文件标签",17,INK,True)
    p.text(18,321,"编辑草稿，保存后写入原文件",11,MUTED)
    p.box(18,350,357,305,SURFACE,18)
    p.photo(34,367,52)
    p.text(99,368,"封面",13,INK,True)
    p.text(99,391,"与文件一致",10,MUTED)
    p.text(299,382,"更换  ›",12,PRIMARY,True)
    p.field(34,433,325,"标题","放过今天的我吧，我真的想歇歇")
    p.field(34,503,325,"艺术家","张星遥")
    p.field(34,573,325,"专辑","张星遥的作品集")
    p.box(18,672,357,47,SURFACE,14)
    p.text(34,682,"更多字段",13,INK,True)
    p.text(105,685,"年份 · 流派 · 歌词",10,MUTED)
    p.chevron(352,695)
    p.save()
    return p.im


def candidate_row(p, y, album, selected=False, alternate=False):
    p.box(18,y,357,84,SURFACE,16,PRIMARY if selected else None)
    p.photo(30,y+11,60,alternate)
    p.text(102,y+11,"放过今天的我吧",14,INK,True)
    p.text(102,y+35,"张星遥 · "+album,11,MUTED,max_w=254)
    p.text(102,y+59,"网易云音乐" if alternate else "酷我音乐",10,MUTED)
    p.text(348,y+30,"›",20,PRIMARY)


def screen_scrape():
    p=Phone(); p.chrome(); p.song(); p.tabbar("scrape")
    p.text(18,294,"搜索歌曲",17,INK,True)
    p.box(18,328,357,216,SURFACE,18)
    p.field(34,342,325,"标题","放过今天的我吧，我真的想歇歇")
    p.field(34,411,325,"艺术家","张星遥")
    p.text(34,490,"来源",11,MUTED)
    p.button(77,481,69,29,"网易云",PRIMARY_PALE,PRIMARY,size=10)
    p.button(153,481,55,29,"酷我",SOFT,MUTED,size=10)
    p.button(270,481,89,30,"搜索",PRIMARY,"#FFFFFF",size=11)
    p.text(18,558,"匹配结果",17,INK,True)
    p.text(302,563,"2 个候选",10,MUTED)
    candidate_row(p,591,"夜色来信",False,True)
    candidate_row(p,684,"张星遥的作品集")
    p.footer()
    return p.im


def diff_checkbox(p,x,y,active=True):
    p.box(x,y,18,18,PRIMARY if active else SURFACE,5,PRIMARY,1)
    if active: p.line([(x+4,y+9),(x+8,y+13),(x+14,y+5)],"#FFFFFF",2)


def screen_diff():
    p=Phone(); p.chrome(); p.song(); p.tabbar("scrape")
    candidate_row(p,330,"夜色来信",True,True)
    p.dim()
    p.box(0,224,393,604,SURFACE,23)
    p.box(174,234,45,4,MUTED,2)
    p.text(20,259,"选择要填入的字段",19,INK,True)
    p.text(20,288,"填入草稿后仍可编辑，保存时才写入文件",11,MUTED)
    p.box(20,318,353,60,BG,13)
    p.photo(28,326,44,True)
    p.text(82,326,"放过今天的我吧",13,INK,True)
    p.text(82,350,"张星遥 · 夜色来信",10,MUTED)
    p.text(20,397,"有差异的字段",13,INK,True)
    p.box(20,427,353,102,BG,13)
    diff_checkbox(p,34,444)
    p.text(62,440,"专辑",13,INK,True)
    p.text(62,468,"当前  张星遥的作品集",11,MUTED)
    p.text(62,493,"填入  夜色来信",12,TERTIARY,True)
    p.box(20,539,353,136,BG,13)
    diff_checkbox(p,34,554)
    p.text(62,550,"封面",13,INK,True)
    p.photo(62,580,72)
    p.text(139,603,"→",16,MUTED,True)
    p.photo(171,580,72,True)
    p.text(62,653,"当前",10,MUTED)
    p.text(171,653,"填入",10,TERTIARY)
    p.box(20,685,353,46,BG,13)
    diff_checkbox(p,34,699,False)
    p.text(62,694,"歌词",12,INK,True)
    p.text(275,699,"暂不填入  ›",10,MUTED)
    p.button(20,765,353,45,"填入草稿（2 项）")
    p.footer()
    return p.im


def screen_draft():
    p=Phone(); p.chrome(); p.song(True,True); p.tabbar("edit")
    p.text(18,296,"编辑草稿",17,INK,True)
    p.text(18,321,"带颜色的字段尚未写入文件",11,MUTED)
    p.box(18,350,357,349,SURFACE,18)
    p.photo(34,365,55,True)
    p.text(102,367,"封面",13,TERTIARY,True)
    p.text(102,390,"已修改 · 点此查看原封面",10,TERTIARY)
    p.text(300,380,"更换  ›",12,PRIMARY,True)
    p.field(34,437,325,"标题","放过今天的我吧，我真的想歇歇")
    p.field(34,509,325,"艺术家","张星遥")
    p.field(34,581,325,"专辑","夜色来信",True,"张星遥的作品集")
    p.box(18,710,357,38,SURFACE,13)
    p.text(34,716,"更多字段",12,INK,True)
    p.text(105,718,"年份 · 流派 · 歌词",10,MUTED)
    p.chevron(351,727)
    p.save(True)
    return p.im


def screen_cover_review():
    p=Phone(); p.chrome(); p.song(True,True); p.tabbar("edit")
    p.dim()
    p.box(0,218,393,610,SURFACE,23)
    p.box(174,228,45,4,MUTED,2)
    p.text(20,254,"核对文件封面",19,INK,True)
    p.text(20,284,"请比较所选图片与文件读回的内容",11,MUTED)
    p.text(20,326,"所选封面",12,INK,True)
    p.text(205,326,"文件读回",12,INK,True)
    p.photo(20,350,168,True)
    p.photo(205,350,168,True)
    p.box(20,541,353,95,PRIMARY_PALE,13)
    p.circle(39,563,8,PRIMARY)
    p.text(58,548,"文字标签已验证",12,INK,True)
    p.text(38,581,"专辑：夜色来信",11,PRIMARY)
    p.text(38,605,"封面仍需你确认",11,MUTED)
    p.text(20,661,"确认后在后台同步曲库，可直接返回",11,MUTED)
    p.button(20,716,353,46,"确认封面一致")
    p.text(20,777,"不一致，返回调整",12,PRIMARY,True)
    p.text(299,777,"重新检查",12,PRIMARY,True)
    p.footer()
    return p.im


def screen_settings():
    p=Phone(); p.chrome("Music Tag 服务",False)
    p.box(18,116,357,68,PRIMARY_PALE,17)
    p.text(34,127,"连接标签服务",16,INK,True)
    p.text(34,152,"连接后即可刮削和编辑音乐文件标签",10,MUTED)
    p.text(18,207,"服务账号",16,INK,True)
    p.box(18,238,357,262,SURFACE,18)
    p.field(34,251,325,"服务地址","https://tag.example.com")
    p.field(34,323,325,"账号","music-admin")
    p.field(34,395,325,"密码","••••••••••••••••")
    p.box(34,468,28,17,OUTLINE,9)
    p.circle(44,476,6,"#FFFFFF")
    p.text(73,464,"允许 HTTP 连接",11,INK)
    p.box(18,519,357,105,SURFACE,16)
    p.text(34,533,"音乐目录映射",13,INK,True)
    p.text(34,558,"默认映射 /app/media，路径不同时可调整",10,MUTED)
    p.text(34,585,"高级设置 · 展开配置路径",11,PRIMARY,True)
    p.chevron(348,598)
    p.text(18,652,"连接成功后显示状态，如需更换请退出连接",10,MUTED)
    p.box(0,744,393,84,SURFACE)
    p.button(18,760,357,48,"测试连接并保存")
    p.footer()
    return p.im


def screen_sync():
    p=Phone(); p.chrome(); p.song(False,True,True)
    p.box(18,201,105,24,PRIMARY_PALE,12)
    p.text(30,204,"原文件已保存",11,PRIMARY,True)
    p.tabbar("edit")
    p.text(18,297,"文件标签",17,INK,True)
    p.text(18,324,"已从文件重新读取，文字和封面均已核对",11,MUTED)
    p.box(18,358,357,64,SURFACE,16)
    p.circle(40,389,10,PRIMARY_PALE)
    p.text(60,371,"文字标签已验证",13,INK,True)
    p.text(60,392,"专辑：夜色来信",11,MUTED)
    p.box(18,435,357,85,SURFACE,16)
    p.photo(32,448,58,True)
    p.text(101,453,"封面已核对",13,INK,True)
    p.text(101,478,"与文件读回内容一致",11,MUTED)
    p.text(18,548,"当前文件",15,INK,True)
    p.box(18,579,357,100,SURFACE,16)
    p.text(34,593,"标题  放过今天的我吧，我真的想歇歇",11,INK,max_w=325)
    p.text(34,621,"艺术家  张星遥",11,INK)
    p.text(34,649,"专辑  夜色来信",11,INK)
    p.box(0,748,393,80,SURFACE)
    p.circle(28,766,6,PRIMARY)
    p.text(44,752,"标签已保存 · 曲库后台同步中",11,PRIMARY,True)
    p.button(18,777,357,43,"完成")
    p.footer()
    return p.im


def screen_connected():
    p=Phone(); p.chrome("Music Tag 服务",False)
    p.box(18,116,357,103,PRIMARY_PALE,18)
    p.circle(41,147,12,PRIMARY)
    p.line([(36,147),(40,151),(47,143)],"#FFFFFF",2)
    p.text(64,129,"已连接",17,INK,True)
    p.text(64,162,"music-admin · tag.example.com",11,MUTED)
    p.text(18,251,"当前服务",16,INK,True)
    p.box(18,282,357,130,SURFACE,16)
    p.text(34,297,"服务地址",11,MUTED)
    p.text(34,321,"https://tag.example.com",13,INK)
    p.line([(34,356),(359,356)],OUTLINE,1)
    p.text(34,368,"账号",11,MUTED)
    p.text(89,368,"music-admin",11,INK)
    p.text(18,442,"音乐目录映射",16,INK,True)
    p.box(18,473,357,84,SURFACE,16)
    p.text(34,487,"两端指向同一份音乐文件",11,INK)
    p.text(34,515,"查看已配置的路径  ›",11,PRIMARY,True)
    p.text(18,585,"要更换服务或路径，请先退出当前连接",10,MUTED)
    p.box(0,744,393,84,SURFACE)
    p.button(18,761,357,47,"退出连接",SURFACE,RED,OUTLINE)
    p.footer()
    return p.im


def pair(left,right,left_name,right_name,name):
    pad=28; gap=28; top=60
    board=Image.new("RGB",((W*2+gap+pad*2)*S,(H+top+pad)*S),"#EBEAF2")
    d=ImageDraw.Draw(board)
    for idx,(screen,label) in enumerate(((left,left_name),(right,right_name))):
        x=(pad+idx*(W+gap))*S
        d.text((x,17*S),label,font=f(17,True),fill=INK)
        board.paste(screen,(x,top*S))
    board.save(HERE/name,optimize=True)


def main():
    screens=[
        ("01-editor-original.png",screen_edit(),"01  文件标签 · 初始"),
        ("02-scrape-results.png",screen_scrape(),"02  刮削匹配 · 候选"),
        ("03-candidate-diff.png",screen_diff(),"03  候选对比 · 选择字段"),
        ("04-editor-draft.png",screen_draft(),"04  编辑草稿 · 待保存"),
        ("05-cover-review.png",screen_cover_review(),"05  封面核对 · 已读回"),
        ("06-service-setup.png",screen_settings(),"06  服务配置 · 首次连接"),
        ("07-sync-progress.png",screen_sync(),"07  保存完成 · 后台同步"),
        ("08-service-connected.png",screen_connected(),"08  服务设置 · 已连接"),
    ]
    for filename,im,_ in screens: im.save(HERE/filename,optimize=True)
    for offset,name in ((0,"a-edit-and-scrape.png"),(2,"b-diff-and-draft.png"),(4,"c-review-and-settings.png"),(6,"d-sync-and-connected.png")):
        pair(screens[offset][1],screens[offset+1][1],screens[offset][2],screens[offset+1][2],name)


if __name__ == "__main__": main()
