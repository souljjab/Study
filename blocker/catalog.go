package main

// 카테고리 묶음. 디자인의 6개 카테고리와 실제 차단 도메인.
var catOrder = []string{"SNS", "영상", "커뮤니티", "쇼핑", "뉴스", "게임"}

type catInfo struct {
	Name    string   `json:"name"`
	Icon    string   `json:"icon"`
	Ex      string   `json:"ex"`
	N       int      `json:"n"`
	Domains []string `json:"-"`
}

var catalog = map[string]*catInfo{
	"SNS": {Icon: "ph-chat-circle-dots", Ex: "instagram · x · tiktok", Domains: []string{
		"instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "messenger.com",
		"twitter.com", "x.com", "twimg.com", "t.co", "tiktok.com", "tiktokcdn.com", "tiktokv.com",
		"threads.net", "snapchat.com", "pinterest.com", "tumblr.com", "band.us", "bsky.app",
	}},
	"영상": {Icon: "ph-play-circle", Ex: "youtube · netflix · chzzk", Domains: []string{
		"youtube.com", "youtu.be", "ytimg.com", "i.ytimg.com", "youtubei.googleapis.com", "youtube-nocookie.com",
		"netflix.com", "twitch.tv", "chzzk.naver.com", "sooplive.co.kr", "afreecatv.com",
		"tving.com", "wavve.com", "watcha.com", "coupangplay.com", "disneyplus.com", "laftel.net",
	}},
	"커뮤니티": {Icon: "ph-users-three", Ex: "dcinside · fmkorea · reddit", Domains: []string{
		"dcinside.com", "gall.dcinside.com", "fmkorea.com", "ruliweb.com", "theqoo.net", "clien.net",
		"ppomppu.co.kr", "mlbpark.donga.com", "instiz.net", "pann.nate.com", "todayhumor.co.kr",
		"bobaedream.co.kr", "etoland.co.kr", "ygosu.com", "humoruniv.com", "arca.live", "dogdrip.net",
		"quasarzone.com", "slrclub.com", "reddit.com", "redd.it", "9gag.com",
	}},
	"쇼핑": {Icon: "ph-shopping-bag", Ex: "coupang · musinsa · 11st", Domains: []string{
		"coupang.com", "musinsa.com", "11st.co.kr", "gmarket.co.kr", "auction.co.kr", "ssg.com",
		"kurly.com", "zigzag.kr", "a-bly.com", "29cm.co.kr", "wconcept.co.kr", "aliexpress.com",
		"temu.com", "amazon.com", "shopping.naver.com", "smartstore.naver.com", "bunjang.co.kr",
	}},
	"뉴스": {Icon: "ph-newspaper", Ex: "news.naver · daum · yna", Domains: []string{
		"news.naver.com", "n.news.naver.com", "entertain.naver.com", "sports.naver.com",
		"news.daum.net", "v.daum.net", "yna.co.kr", "chosun.com", "joongang.co.kr", "donga.com",
		"hani.co.kr", "khan.co.kr", "mk.co.kr", "hankyung.com", "ytn.co.kr",
	}},
	"게임": {Icon: "ph-game-controller", Ex: "steam · op.gg · inven", Domains: []string{
		"store.steampowered.com", "steamcommunity.com", "op.gg", "inven.co.kr", "leagueoflegends.com",
		"battle.net", "blizzard.com", "epicgames.com", "nexon.com", "roblox.com", "fow.kr", "onstove.com",
	}},
}

func init() {
	for name, c := range catalog {
		c.Name = name
		c.N = len(c.Domains)
	}
}

func catList() []*catInfo {
	out := make([]*catInfo, 0, len(catOrder))
	for _, n := range catOrder {
		out = append(out, catalog[n])
	}
	return out
}
