package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog for the YouTube spider: home classes, the keyword each class searches for, and
 * the filter groups shown on a category page.
 *
 * <p>Every class is a saved search rather than a real YouTube taxonomy, so a filter value is just
 * an extra keyword appended to the class keyword.
 */
final class YTCatalog {

    private YTCatalog() {
    }

    private static final String[][] CLASSES = {
            {"4K", "4K"},
            {"HDR", "HDR"},
            {"自然", "自然"},
            {"动画片", "动画片"},
            {"短剧", "短剧"},
            {"剧集", "剧集"},
            {"电影", "电影"},
            {"纪录片", "纪录片"},
            {"放松", "放松"},
            {"16K HDR", "16K HDR"},
            {"科技", "科技"},
            {"解说", "解说"},
    };

    private static final Map<String, String> QUERY = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        QUERY.put("动画片", "动画 国漫 anime cartoon");
        QUERY.put("短剧", "短剧");
        QUERY.put("剧集", "电视剧 剧集 drama");
        QUERY.put("电影", "电影 movie");
        QUERY.put("纪录片", "纪录片 documentary");
        QUERY.put("放松", "放松 冥想 自然 音乐 relax meditation nature");
        QUERY.put("4K", "4K video");
        QUERY.put("HDR", "HDR video");
        QUERY.put("自然", "大自然 风景 动物 世界 nature wildlife scenery");
        QUERY.put("16K HDR", "16K HDR video");
        QUERY.put("科技", "科技 technology");
        QUERY.put("解说", "电影解说 故事解说");

        ALIASES.put("動畫片", "动画片");
        ALIASES.put("劇集", "剧集");
        ALIASES.put("電影", "电影");
        ALIASES.put("紀錄片", "纪录片");
        ALIASES.put("解說", "解说");
        ALIASES.put("movie", "电影");
        ALIASES.put("game", "科技");
        ALIASES.put("documentary", "纪录片");
    }

    static List<Class> classes() {
        List<Class> list = new ArrayList<>();
        for (String[] item : CLASSES) list.add(new Class(item[0], item[1]));
        return list;
    }

    /** @return the class id after alias folding, e.g. {@code 電影 -> 电影}. */
    static String normalizeId(String cid) {
        String raw = cid == null ? "" : cid.trim();
        String mapped = ALIASES.get(raw);
        return mapped == null ? raw : mapped;
    }

    /** Builds the search keyword for a class id plus the selected filter values. */
    static String keyword(String cid, Map<String, String> filters) {
        String id = normalizeId(cid);
        List<String> terms = new ArrayList<>();
        String base = QUERY.get(id);
        if (base == null) base = QUERY.get(cid == null ? "" : cid.trim());
        if (base == null || base.isEmpty()) base = id.isEmpty() ? (cid == null ? "" : cid.trim()) : id;
        if (!base.isEmpty()) terms.add(base);
        if (filters != null) {
            for (String value : filters.values()) {
                String term = normalizeTerm(value);
                if (!term.isEmpty()) terms.add(term);
            }
        }
        StringBuilder sb = new StringBuilder();
        List<String> seen = new ArrayList<>();
        for (String term : terms) {
            String trimmed = term.trim();
            if (trimmed.isEmpty() || seen.contains(trimmed)) continue;
            seen.add(trimmed);
            if (sb.length() > 0) sb.append(' ');
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private static String normalizeTerm(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private static Filter group(String key, String name, String[][] pairs) {
        List<Filter.Value> values = new ArrayList<>();
        values.add(new Filter.Value("全部", ""));
        for (String[] pair : pairs) values.add(new Filter.Value(pair[0], pair[1]));
        return new Filter(key, name, values);
    }

    /** Year first, then the supplied groups. Years run from 2026 back to 1958, as in the source. */
    private static List<Filter> withYear(Filter... groups) {
        List<Filter.Value> years = new ArrayList<>();
        years.add(new Filter.Value("全部", ""));
        for (int year = 2026; year > 1957; year--) years.add(new Filter.Value(String.valueOf(year), String.valueOf(year)));
        List<Filter> list = new ArrayList<>();
        list.add(new Filter("year", "年份", years));
        list.addAll(Arrays.asList(groups));
        return list;
    }

    private static List<Filter> only(Filter... groups) {
        return new ArrayList<>(Arrays.asList(groups));
    }

    static LinkedHashMap<String, List<Filter>> filters() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        filters.put("动画片", withYear(
                group("topic", "中文", new String[][]{
                        {"国漫", "国漫 3D 动画"}, {"儿童早教", "儿童早教"}, {"儿童歌曲", "儿童歌曲"},
                        {"儿童音乐", "儿童音乐"}, {"儿童绘画", "儿童绘画"}, {"宝宝巴士", "宝宝巴士"},
                        {"儿歌多多", "儿歌多多"}, {"英语启蒙", "儿童英语启蒙"}, {"安全教育", "儿童安全教育"},
                }),
                group("channel", "频道", new String[][]{
                        {"小猪佩奇", "@PeppaPigChineseOfficial 小猪佩奇 中文"}, {"CoComelon", "@CoComelon"},
                        {"国漫合集", "Anime ENG SUB 合集 国漫"}, {"阅文动漫", "@yuewenanimation"},
                        {"哔哩动漫", "@madebybilibili 哔哩动漫"}, {"腾讯动漫", "@TencentVideoAnimation"},
                        {"优酷动漫", "@youkuanimation 优酷动漫"}, {"爱奇艺动漫", "@iQIYIAnime 爱奇艺动漫"},
                })));
        filters.put("短剧", withYear(
                group("region", "地区/平台", new String[][]{
                        {"抖音", "抖音 短剧"}, {"快手", "快手 短剧"}, {"大陆", "大陆 短剧"},
                        {"香港", "香港 短剧"}, {"澳门", "澳门 短剧"}, {"台湾", "台湾 短剧"},
                        {"新加坡", "新加坡 短剧"}, {"马来西亚", "马来西亚 短剧"}, {"泰国", "泰国 短剧"},
                        {"越南", "越南 短剧"}, {"印度", "印度 短剧"}, {"韩国", "韩国 短剧"},
                        {"日本", "日本 短剧"}, {"欧美", "欧美 短剧"}, {"腾讯", "腾讯 短剧"},
                        {"爱奇艺", "爱奇艺 短剧"}, {"优酷", "优酷 短剧"}, {"芒果", "芒果TV 短剧"}, {"搜狐", "搜狐 短剧"},
                }),
                group("topic", "题材/频道", new String[][]{
                        {"都市", "@Urbanshort-TV 都市 短剧"}, {"爱情", "爱情 短剧"}, {"复仇", "复仇 短剧"},
                        {"穿越", "穿越 短剧"}, {"喜剧", "喜剧 短剧"}, {"奇幻", "奇幻 短剧"},
                        {"九酱爱追剧", "@NineSauceDramaTV"}, {"百万好剧场", "@1-pw5ox"},
                        {"咖啡追剧", "@coffeedrama605"}, {"斗罗短剧", "@DouluoDrama123 斗罗短剧"},
                        {"嘟嘟剧场", "@DUDUJUCHANG"}, {"牛牛短剧", "@niuniuduanju"},
                })));
        filters.put("剧集", withYear(
                group("region", "中文", new String[][]{
                        {"华语热播", "华语热播电视剧官方频道"}, {"粤剧", "粤剧 剧集"}, {"TVB", "@TVB"},
                        {"国剧放映社", "国剧放映社"}, {"大陆", "大陆 剧集"}, {"腾讯", "腾讯 剧集"},
                        {"爱奇艺", "爱奇艺 剧集"}, {"优酷", "优酷 剧集"}, {"芒果", "芒果TV 剧集"},
                        {"搜狐", "搜狐 剧集"}, {"港台", "港台 剧集"}, {"美国", "美国 剧集"},
                        {"韩国", "韩国 剧集"}, {"日本", "日本 剧集"}, {"英国", "英国 剧集"},
                }),
                group("platform", "平台", new String[][]{
                        {"Netflix", "netflix drama"}, {"Disney", "disney drama"}, {"Apple", "apple drama"},
                        {"Amazon", "amazon drama"}, {"HBO", "hbo drama"},
                })));
        filters.put("电影", withYear(
                group("region", "地区/平台", new String[][]{
                        {"大陆", "大陆 电影"}, {"腾讯", "腾讯 电影"}, {"爱奇艺", "爱奇艺 电影"},
                        {"优酷", "优酷 电影"}, {"芒果", "芒果TV 电影"}, {"搜狐", "搜狐 电影"},
                        {"港台", "港台 电影"}, {"美国", "美国 movie"}, {"韩国", "韩国 电影"},
                        {"日本", "日本 电影"}, {"英国", "英国 movie"},
                }),
                group("platform", "平台", new String[][]{
                        {"YouTube Movies", "youtube movies"}, {"Netflix", "netflix movie"}, {"Disney", "disney movie"},
                        {"Apple", "apple movie"}, {"Amazon", "amazon movie"}, {"HBO", "hbo movie"},
                })));
        filters.put("纪录片", withYear(
                group("topic", "主题", new String[][]{
                        {"历史", "历史 纪录片"}, {"野性", "野性 纪录片 wild documentary"},
                        {"地球", "地球 纪录片 earth documentary"}, {"宇宙", "宇宙 纪录片 universe documentary"},
                        {"海洋", "海洋 纪录片 oceans documentary"}, {"人文", "人文 纪录片"},
                        {"战争", "战争 纪录片 war documentary"}, {"BBC", "BBC 纪录片 documentary"},
                        {"国家地理", "国家地理 National Geographic documentary"}, {"Netflix", "netflix 纪录片 documentary"},
                })));
        filters.put("放松", only(
                group("topic", "主题", new String[][]{
                        {"冥想", "冥想 放松 meditation relax"}, {"睡眠", "睡眠 放松 sleep relax"},
                        {"白噪音", "白噪音 放松 white noise"}, {"自然声音", "自然 声音 放松 nature sounds"},
                        {"雨声", "雨声 放松 rain sounds"}, {"海浪", "海浪 放松 ocean waves"},
                })));
        filters.put("4K", only(
                group("topic", "主题", new String[][]{
                        {"风景", "4K 风景 scenery"}, {"城市", "4K 城市 city walk"}, {"旅行", "4K travel"},
                        {"动物", "4K wildlife animals"}, {"航拍", "4K drone aerial"}, {"演示片", "4K demo video"},
                })));
        filters.put("HDR", only(
                group("topic", "主题", new String[][]{
                        {"风景", "HDR 风景 scenery"}, {"自然", "HDR nature"}, {"动物", "HDR wildlife animals"},
                        {"城市", "HDR city"}, {"演示片", "HDR demo video"}, {"放松", "HDR relax"},
                })));
        filters.put("自然", only(
                group("topic", "主题", new String[][]{
                        {"风景", "大自然 风景 nature scenery"}, {"动物世界", "动物世界 wildlife documentary"},
                        {"海洋", "海洋 自然 ocean nature"}, {"森林", "森林 自然 forest nature"},
                        {"鸟类", "鸟类 自然 birds nature"}, {"地球", "地球 自然 earth nature"},
                        {"国家地理", "National Geographic nature wildlife"}, {"BBC Earth", "BBC Earth nature"},
                })));
        filters.put("16K HDR", only(
                group("topic", "风景", new String[][]{
                        {"运动", "GoPro 极限自行车 翼装飞行"}, {"风景", "hdr 大自然 风景"},
                        {"Links TV", "@linksphotograph Links TV hdr"}, {"放松", "hdr 放松"},
                        {"动物世界", "hdr Carnivorous Animals 动物世界"}, {"深海世界", "hdr Invertebrate Fish 深海世界"},
                        {"飞禽走兽", "hdr Birds of Prey Birds"}, {"生物世界", "hdr Amphibians Reptiles 生物世界"},
                })));
        filters.put("科技", only(
                group("topic", "主题", new String[][]{
                        {"AI", "人工智能 AI technology"}, {"数码", "数码 科技 technology"},
                        {"手机", "手机 评测 technology"}, {"电脑", "电脑 科技 technology"},
                        {"汽车科技", "汽车 科技 technology"}, {"太空", "航天 太空 technology"},
                })));
        filters.put("解说", only(
                group("channel", "频道主", new String[][]{
                        {"宇哥侃故事", "@yuge"}, {"零度解说", "@lingdujieshuo"},
                })));
        return filters;
    }
}
