package com.autopublisher;

import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class UniversalCurationPublisher {

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String CP_ACCESS_KEY = System.getenv("COUPANG_ACCESS_KEY") != null ? System.getenv("COUPANG_ACCESS_KEY").trim() : "";
    private static final String CP_SECRET_KEY = System.getenv("COUPANG_SECRET_KEY") != null ? System.getenv("COUPANG_SECRET_KEY").trim() : "";
    private static final String ALI_APP_KEY = System.getenv("ALI_APP_KEY") != null ? System.getenv("ALI_APP_KEY").trim() : "";
    private static final String ALI_APP_SECRET = System.getenv("ALI_APP_SECRET") != null ? System.getenv("ALI_APP_SECRET").trim() : "";
    private static final String ALI_TRACKING_ID = System.getenv("ALI_TRACKING_ID") != null ? System.getenv("ALI_TRACKING_ID").trim() : "";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build();

    static class CategoryRule {
        String keyword;       // 글로벌(영문) 검색어
        String keywordKr;     // 한국(국문) 검색어
        String minPriceKrw;
        List<String> excludeWords;

        public CategoryRule(String keyword, String keywordKr, String minPriceKrw, String... excludeWords) {
            this.keyword = keyword;
            this.keywordKr = keywordKr;
            this.minPriceKrw = minPriceKrw;
            this.excludeWords = Arrays.asList(excludeWords);
        }
    }

    static class Product {
        String name; String price; String url; String source; String imageUrl;
        public Product(String name, String price, String url, String source, String imageUrl) {
            this.name = name.replaceAll("<[^>]*>", ""); this.price = price; this.url = url; this.source = source; this.imageUrl = imageUrl;
        }
    }

    // --- [1] 쿠팡 파트너스 API (한국 타겟) ---
    public static List<Product> searchCoupang(CategoryRule rule, int limit) { // 오타 수정 완료
        List<Product> list = new ArrayList<>();
        if (CP_ACCESS_KEY.isEmpty() || CP_SECRET_KEY.isEmpty()) return list;

        try {
            String method = "GET";
            String path = "/v2/providers/affiliate_open_api/apis/openapi/products/search";
            String encodedKeyword = java.net.URLEncoder.encode(rule.keywordKr, "UTF-8").replace("+", "%20");

            // [핵심 해결] 서명용(물음표 제외)과 통신용(물음표 포함) 문자열 분리
            String queryForHmac = "keyword=" + encodedKeyword + "&limit=" + limit;
            String queryForUrl = "?" + queryForHmac;

            String datetime = ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'"));

            // 암호를 만들 때는 '?'가 빠진 queryForHmac 결합
            String message = datetime + method + path + queryForHmac;

            SecretKeySpec secretKeySpec = new SecretKeySpec(CP_SECRET_KEY.getBytes("UTF-8"), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKeySpec);

            byte[] rawHmac = mac.doFinal(message.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : rawHmac) {
                hexString.append(String.format("%02x", b));
            }
            String signature = hexString.toString();

            String authorization = "CEA algorithm=HmacSHA256, access-key=" + CP_ACCESS_KEY + ", signed-date=" + datetime + ", signature=" + signature;

            // 실제 통신을 보낼 때는 '?'가 포함된 queryForUrl 결합
            Request request = new Request.Builder()
                    .url("https://api-gateway.coupang.com" + path + queryForUrl)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json;charset=UTF-8").get().build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject res = JsonParser.parseString(response.body().string()).getAsJsonObject();
                    if (res.has("data") && !res.get("data").isJsonNull()) {
                        com.google.gson.JsonArray items = res.getAsJsonObject("data").getAsJsonArray("productData");
                        for (int i = 0; i < items.size(); i++) {
                            JsonObject item = items.get(i).getAsJsonObject();
                            String title = item.get("productName").getAsString();
                            boolean isExcluded = rule.excludeWords.stream().anyMatch(w -> title.toLowerCase().contains(w));
                            if (!isExcluded) {
                                list.add(new Product(title, item.get("productPrice").getAsString(), item.get("productUrl").getAsString(), "Coupang", item.get("productImage").getAsString()));
                            }
                        }
                    }
                } else {
                    System.out.println("❌ 쿠팡 API 거절 코드: " + response.code());
                    if (response.body() != null) System.out.println("❌ 쿠팡 API 에러 상세: " + response.body().string());
                }
            }
        } catch (Exception e) { System.out.println("⚠️ 쿠팡 API 에러: " + e.getMessage()); }
        return list;
    }

    // --- [2] 알리익스프레스 API (글로벌 타겟) ---
    public static List<Product> searchAliExpress(CategoryRule rule, int limit) {
        List<Product> list = new ArrayList<>();
        if (ALI_APP_KEY.isEmpty()) return list;

        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));

            java.util.Map<String, String> params = new java.util.TreeMap<>();
            params.put("method", "aliexpress.affiliate.product.query");
            params.put("app_key", ALI_APP_KEY);
            params.put("sign_method", "md5");
            params.put("timestamp", sdf.format(new java.util.Date()));
            params.put("format", "json");
            params.put("v", "2.0");
            params.put("keywords", rule.keyword);
            params.put("target_language", "EN");
            params.put("target_currency", "KRW");
            params.put("min_sale_price", rule.minPriceKrw);
            params.put("tracking_id", ALI_TRACKING_ID);
            params.put("page_size", "20");

            StringBuilder signStr = new StringBuilder(ALI_APP_SECRET);
            for (java.util.Map.Entry<String, String> entry : params.entrySet()) signStr.append(entry.getKey()).append(entry.getValue());
            signStr.append(ALI_APP_SECRET);

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(signStr.toString().getBytes("UTF-8"));
            StringBuilder signHex = new StringBuilder();
            for (byte b : digest) signHex.append(String.format("%02X", b));
            params.put("sign", signHex.toString());

            HttpUrl.Builder urlBuilder = HttpUrl.parse("https://api-sg.aliexpress.com/sync").newBuilder();
            for (java.util.Map.Entry<String, String> entry : params.entrySet()) urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());

            Request request = new Request.Builder().url(urlBuilder.build()).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject res = JsonParser.parseString(response.body().string()).getAsJsonObject();
                    if (res.has("aliexpress_affiliate_product_query_response")) {
                        JsonObject queryRes = res.getAsJsonObject("aliexpress_affiliate_product_query_response");
                        if (queryRes.getAsJsonObject("resp_result").get("resp_code").getAsInt() == 200) {
                            com.google.gson.JsonArray items = queryRes.getAsJsonObject("resp_result").getAsJsonObject("result").getAsJsonObject("products").getAsJsonArray("product");
                            for (int i = 0; i < items.size() && list.size() < limit; i++) {
                                JsonObject item = items.get(i).getAsJsonObject();
                                String title = item.get("product_title").getAsString();
                                String titleLower = title.toLowerCase();
                                if (titleLower.contains("replacement") || titleLower.contains("part") || titleLower.contains("accessory")) continue;
                                boolean isExcluded = rule.excludeWords.stream().anyMatch(titleLower::contains);
                                if (isExcluded) continue;

                                list.add(new Product(title, item.get("target_sale_price").getAsString(), item.get("promotion_link").getAsString(), "AliExpress", item.has("product_main_image_url") ? item.get("product_main_image_url").getAsString() : ""));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { System.out.println("⚠️ 알리 API 에러: " + e.getMessage()); }
        return list;
    }

    // --- [3] AI 리뷰 작성 (언어 분리) ---
    public static String generateAiReview(String keyword, List<Product> products, boolean isKorean) throws Exception {
        StringBuilder prompt = new StringBuilder();
        if (isKorean) {
            prompt.append("당신은 IT/가전 전문 리뷰어입니다. 다음 5개의 제품을 바탕으로 구매를 유도하는 매력적인 큐레이션 글을 작성해 주세요.\n\n");
            for (int i = 0; i < products.size(); i++) prompt.append(i + 1).append(". ").append(products.get(i).name).append("\n");
            prompt.append("\n[요구사항]\n- 서론: 구매 가이드 (2~3문장)\n- 본문: 각 제품의 장점 강조 (마크다운 사용)\n- 결론: 요약 추천\n- 언어: 반드시 100% 자연스러운 한국어로 작성하세요.");
        } else {
            prompt.append("You are a professional tech reviewer. Write a converting curation article entirely in English based on these 5 products.\n\n");
            for (int i = 0; i < products.size(); i++) prompt.append(i + 1).append(". ").append(products.get(i).name).append("\n");
            prompt.append("\n[Requirements]\n- Intro: Buying guide\n- Body: Emphasize features using markdown\n- Conclusion: Summary\n- Language: MUST be written entirely in English. Do not use Korean.");
        }

        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", prompt.toString());
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray(); messages.add(message);
        JsonObject jsonBody = new JsonObject(); jsonBody.addProperty("model", "gpt-4o-mini"); jsonBody.add("messages", messages);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + OPENAI_API_KEY).post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonBody.toString())).build();
        try (Response response = client.newCall(request).execute()) {
            return JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        }
    }

    // --- [4] 마크다운 파일 저장 (폴더 분리 및 공정위 문구 준수) ---
    public static void saveMarkdown(String titleTopic, String content, List<Product> products, String folderName, boolean isKorean) throws Exception {
        Date now = new Date();
        String dateFormatted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(now);
        String fileDatePrefix = new SimpleDateFormat("yyyy-MM-dd").format(now);

        StringBuilder md = new StringBuilder();
        md.append("---\n");

        // 1. [핵심 수정] 한국어 포스팅 제목에 (광고) 명시
        md.append("title: \"").append(isKorean ? "[광고] 가성비 최고! 추천 " + titleTopic + " Top 5" : "Top 5 Best " + titleTopic + " (Highly Recommended)").append("\"\n");
        md.append("date: ").append(dateFormatted).append("\n");
        md.append("categories: [\"").append(isKorean ? "IT/가전" : "Tech Gadgets").append("\"]\n");
        md.append("tags: [\"").append(titleTopic).append(isKorean ? "\", \"추천\", \"리뷰\"]\n" : "\", \"Review\", \"Best Deals\"]\n");
        if (!products.isEmpty() && !products.get(0).imageUrl.isEmpty()) {
            md.append("cover:\n  image: \"").append(products.get(0).imageUrl).append("\"\n");
        }
        md.append("---\n\n");

        // 2. [핵심 수정] 본문이 시작되기 전, 최상단에 굵고 명확하게 쿠팡 파트너스 대가성 문구 삽입
        if (isKorean) {
            md.append("> **이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.**\n\n");
        }

        md.append(content).append("\n\n---\n");
        md.append(isKorean ? "### 🛒 최저가 및 상세 정보 확인하기\n\n" : "### 🛒 Best Deals & Latest Prices\n\n");

        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            md.append("#### ").append(i + 1).append(". ").append(p.name).append("\n\n");
            if (!p.imageUrl.isEmpty()) md.append("<img src=\"").append(p.imageUrl).append("\" width=\"400\" style=\"border-radius:8px; margin-bottom:10px;\" />\n\n");
            md.append("👉 **[").append(isKorean ? p.source + "에서 현재 가격 확인하기" : "Check Current Price on " + p.source).append("](").append(p.url).append(")**\n\n");
        }

        // 3. 하단 글로벌(알리)용 문구 유지 (한국어는 상단에 명시했으므로 글로벌만 출력)
        md.append("---\n<br>");
        if (!isKorean) {
            md.append("<span style='font-size:12px; color:#888;'>*Disclosure: This post contains affiliate links. We may earn a commission at no extra cost to you.</span>\n");
        }

        File dir = new File("posts/" + folderName);
        if (!dir.exists()) dir.mkdirs();

        String safeName = titleTopic.toLowerCase().replaceAll("[^a-z0-9가-힣]", "-").replaceAll("-+", "-");
        Path filePath = Paths.get("posts/" + folderName, fileDatePrefix + "-" + safeName + ".md");
        Files.write(filePath, md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("✅ 저장 완료 (" + folderName + "): " + filePath.toAbsolutePath());
    }

    public static void main(String[] args) {
        CategoryRule[] rules = {
                new CategoryRule("Air Purifier", "공기청정기", "100000", "filter", "diffuser", "humidifier", "heater"),
                new CategoryRule("Robot Vacuum Cleaner", "로봇청소기", "150000", "mop", "brush", "dust bag", "battery"),
                new CategoryRule("Smart Home Security Camera", "홈카메라", "35000", "bracket", "mount", "sd card", "cable"),
                new CategoryRule("Ergonomic Mechanical Keyboard", "기계식키보드", "50000", "keycap", "switch", "lube", "tester"),
                new CategoryRule("Automatic Pet Feeder", "자동급식기", "40000", "filter", "bowl", "mat", "desiccant"),
                new CategoryRule("Portable Power Station", "파워뱅크", "100000", "bag", "cover", "adapter", "cable"),
                new CategoryRule("Wireless CarPlay Adapter", "무선카플레이", "30000", "cable", "mount", "case")
        };

        CategoryRule todayRule = rules[LocalDate.now().getDayOfYear() % rules.length];
        System.out.println("🚀 오늘의 테마: " + todayRule.keyword + " / " + todayRule.keywordKr);

        try {
            // 1. 글로벌 포스팅 (알리익스프레스 + 영문)
            List<Product> aliProducts = searchAliExpress(todayRule, 5);
            if (!aliProducts.isEmpty()) {
                String engReview = generateAiReview(todayRule.keyword, aliProducts, false);
                saveMarkdown(todayRule.keyword, engReview, aliProducts, "global", false);
            }

            // 2. 국내 포스팅 (쿠팡 + 국문)
            List<Product> coupangProducts = searchCoupang(todayRule, 5);
            if (!coupangProducts.isEmpty()) {
                String korReview = generateAiReview(todayRule.keywordKr, coupangProducts, true);
                saveMarkdown(todayRule.keywordKr, korReview, coupangProducts, "korea", true);
            }

            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}