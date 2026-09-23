package com.autopublisher;

import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class UniversalCurationPublisher {

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");

    // 쿠팡 설정 유지
    private static final String CP_ACCESS_KEY = "쿠팡_액세스키";
    private static final String CP_SECRET_KEY = "쿠팡_시크릿키";

    private static final String ALI_APP_KEY = System.getenv("ALI_APP_KEY") != null ? System.getenv("ALI_APP_KEY").trim() : "";
    private static final String ALI_APP_SECRET = System.getenv("ALI_APP_SECRET") != null ? System.getenv("ALI_APP_SECRET").trim() : "";
    private static final String ALI_TRACKING_ID = System.getenv("ALI_TRACKING_ID") != null ? System.getenv("ALI_TRACKING_ID").trim() : "";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    // =====================================================
    // [핵심 추가] 카테고리별 맞춤 필터 및 최소 가격 설정 클래스
    // =====================================================
    static class CategoryRule {
        String keyword;
        String minPriceKrw;
        List<String> excludeWords;

        public CategoryRule(String keyword, String minPriceKrw, String... excludeWords) {
            this.keyword = keyword;
            this.minPriceKrw = minPriceKrw;
            this.excludeWords = Arrays.asList(excludeWords);
        }
    }

    static class Product {
        String name;
        String price;
        String url;
        String source;

        public Product(String name, String price, String url, String source) {
            this.name = name.replaceAll("<[^>]*>", "");
            this.price = price;
            this.url = url;
            this.source = source;
        }
    }

    interface AffiliateProvider {
        List<Product> searchProducts(CategoryRule rule, int limit) throws Exception;
    }

    // --- [1] 네이버 쇼핑 미끼 ---
    static class NaverDecoyProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(CategoryRule rule, int limit) {
            // 기존 로직 유지...
            return new ArrayList<>();
        }
    }

    // --- [2] 쿠팡 파트너스 API ---
    static class CoupangProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(CategoryRule rule, int limit) throws Exception {
            List<Product> list = new ArrayList<>();
            list.add(new Product("[쿠팡 추천] " + rule.keyword, "45000", "https://coupa.ng/xxxx", "Coupang"));
            return list;
        }
    }

    // --- [3] 알리익스프레스 API (카테고리 룰 적용) ---
    static class AliExpressProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(CategoryRule rule, int limit) throws Exception {
            List<Product> list = new ArrayList<>();
            int pageNo = 1;
            int maxPages = 5;

            while (list.size() < limit && pageNo <= maxPages) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));
                String timestamp = sdf.format(new java.util.Date());

                java.util.Map<String, String> params = new java.util.TreeMap<>();
                params.put("method", "aliexpress.affiliate.product.query");
                params.put("app_key", ALI_APP_KEY);
                params.put("sign_method", "md5");
                params.put("timestamp", timestamp);
                params.put("format", "json");
                params.put("v", "2.0");
                params.put("keywords", rule.keyword);
                params.put("target_language", "EN"); // 영문 글 생성 목적
                params.put("target_currency", "KRW"); // 검증된 원화 기준으로 가격 필터링

                // [동적 할당] 카테고리별 지정된 최소 금액 적용
                params.put("min_sale_price", rule.minPriceKrw);

                params.put("category_ids", "6, 44, 509");
                params.put("tracking_id", ALI_TRACKING_ID);
                params.put("page_size", "40");
                params.put("page_no", String.valueOf(pageNo));

                StringBuilder signStr = new StringBuilder(ALI_APP_SECRET);
                for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                    signStr.append(entry.getKey()).append(entry.getValue());
                }
                signStr.append(ALI_APP_SECRET);

                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(signStr.toString().getBytes("UTF-8"));
                StringBuilder signHex = new StringBuilder();
                for (byte b : digest) { signHex.append(String.format("%02X", b)); }

                params.put("sign", signHex.toString());

                HttpUrl.Builder urlBuilder = HttpUrl.parse("https://api-sg.aliexpress.com/sync").newBuilder();
                for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                    urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
                }

                Request request = new Request.Builder().url(urlBuilder.build()).get().build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) break;

                    String responseBody = response.body().string();
                    JsonObject res = JsonParser.parseString(responseBody).getAsJsonObject();

                    if (res.has("aliexpress_affiliate_product_query_response")) {
                        JsonObject queryRes = res.getAsJsonObject("aliexpress_affiliate_product_query_response");
                        if (queryRes.getAsJsonObject("resp_result").get("resp_code").getAsInt() == 200) {
                            com.google.gson.JsonArray items = queryRes.getAsJsonObject("resp_result").getAsJsonObject("result").getAsJsonObject("products").getAsJsonArray("product");

                            for (int i = 0; i < items.size(); i++) {
                                if (list.size() >= limit) break;

                                JsonObject item = items.get(i).getAsJsonObject();
                                String title = item.get("product_title").getAsString();
                                String titleLower = title.toLowerCase();

                                // 1. 공통 악세서리 금지어 방어
                                if (titleLower.contains("replacement") || titleLower.contains("part") ||
                                        titleLower.contains("accessory") || titleLower.contains("module")) {
                                    continue;
                                }

                                // 2. 카테고리 전용 정밀 방어 (CategoryRule 배열에서 가져옴)
                                boolean isExcluded = false;
                                for (String excludeWord : rule.excludeWords) {
                                    if (titleLower.contains(excludeWord)) {
                                        isExcluded = true;
                                        break;
                                    }
                                }
                                if (isExcluded) continue;

                                list.add(new Product(
                                        title,
                                        item.get("target_sale_price").getAsString(),
                                        item.get("promotion_link").getAsString(),
                                        "AliExpress"
                                ));
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ 알리 API 에러: " + e.getMessage());
                    break;
                }
                pageNo++;
            }
            return list;
        }
    }

    private static String generateAiReview(String keyword, List<Product> products) throws Exception {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            return "⚠️ OpenAI API Key is missing.";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional tech affiliate copywriter and SEO expert. ");
        prompt.append("Write a highly converting product curation article entirely in English based on the following 5 '").append(keyword).append("' products.\n\n");

        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            prompt.append(i + 1).append(". ").append(p.name).append(" (Price: ₩").append(p.price).append(")\n");
        }

        prompt.append("\n[Strict Requirements]\n");
        prompt.append("- Introduction: 2-3 sentences of a buying guide for ").append(keyword).append(".\n");
        prompt.append("- Body: Introduce each product naturally in 2-3 sentences emphasizing its features (Use markdown bullet points).\n");
        prompt.append("- Conclusion: Final summary of which product suits which type of user.\n");
        prompt.append("- Tone: Professional, engaging, and persuasive.\n");
        prompt.append("- Note: Do not include actual URLs in the text (they are appended at the bottom automatically).\n");
        prompt.append("- Language: MUST be written entirely in English. Do not use Korean.\n");

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt.toString());

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-4o-mini");
        jsonBody.add("messages", messages);
        jsonBody.addProperty("temperature", 0.7);

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(mediaType, jsonBody.toString());

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI API Error: " + response.code() + " " + response.body().string());
            }
            String resBody = response.body().string();
            JsonObject resJson = JsonParser.parseString(resBody).getAsJsonObject();
            return resJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        }
    }

    public static void main(String[] args) {
        System.setProperty("https.protocols", "TLSv1.2");

        // [핵심 변경점] 7개 카테고리별 정밀 룰 셋업 (키워드, 최소가격(KRW), 전용금지어)
        CategoryRule[] rules = {
                new CategoryRule("Air Purifier", "100000", "filter", "diffuser", "humidifier", "heater"),
                new CategoryRule("Robot Vacuum Cleaner", "150000", "mop", "brush", "dust bag", "battery"),
                new CategoryRule("Smart Home Security Camera", "35000", "bracket", "mount", "sd card", "cable"),
                new CategoryRule("Ergonomic Mechanical Keyboard", "50000", "keycap", "switch", "lube", "tester"),
                new CategoryRule("Automatic Pet Feeder", "40000", "filter", "bowl", "mat", "desiccant"),
                new CategoryRule("Portable Power Station", "100000", "bag", "cover", "adapter", "cable"),
                new CategoryRule("Wireless CarPlay Adapter", "30000", "cable", "mount", "case")
        };

        // 날짜 기반으로 순회하며 오늘의 룰 꺼내기
        int dayOfYear = LocalDate.now().getDayOfYear();
        CategoryRule targetRule = rules[dayOfYear % rules.length];

        System.out.println("🚀 Today's Category: " + targetRule.keyword + " (Min Price: ₩" + targetRule.minPriceKrw + ")");

        try {
            List<AffiliateProvider> providers = new ArrayList<>();
            // providers.add(new NaverDecoyProvider());
            // providers.add(new CoupangProvider());
            providers.add(new AliExpressProvider());

            List<Product> curatedProducts = new ArrayList<>();
            for (AffiliateProvider provider : providers) {
                curatedProducts.addAll(provider.searchProducts(targetRule, 5));
            }

            System.out.println("📦 Collected Valid Products: " + curatedProducts.size());

            if (curatedProducts.isEmpty()) {
                System.err.println("❌ No valid products found. (Blocked by price/filters). Aborting.");
                System.exit(1);
            }

            System.out.println("Generating English Review via OpenAI...");
            String aiReview = generateAiReview(targetRule.keyword, curatedProducts);

            Date now = new Date();
            String dateFormatted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(now);

            StringBuilder mdContent = new StringBuilder();
            mdContent.append("---\n");
            mdContent.append("title: \"Top 5 Best ").append(targetRule.keyword).append(" (Highly Recommended)\"\n");
            mdContent.append("date: ").append(dateFormatted).append("\n");
            mdContent.append("category: \"Tech Gadgets\"\n");
            mdContent.append("tags: [\"").append(targetRule.keyword).append("\", \"Best Deals\", \"Review\"]\n");
            mdContent.append("---\n\n");

            mdContent.append(aiReview).append("\n\n");
            mdContent.append("---\n### 🛒 Best Deals\n\n");

            for (Product p : curatedProducts) {
                // 한화로 수집하더라도 프론트는 달러 기호나 범용 텍스트로 치환하여 글로벌 느낌 유지
                mdContent.append(String.format("- **[%s]** %s - [Check Current Price Here](%s)\n",
                        p.source, p.name, p.url));
            }
            mdContent.append("\n<br><span style='font-size:12px; color:#888;'>*Disclosure: This post contains affiliate links. We may earn a commission at no extra cost to you.</span>\n");

            String fileDatePrefix = new SimpleDateFormat("yyyy-MM-dd").format(now);
            String seoFileName = fileDatePrefix + "-" + targetRule.keyword.toLowerCase().replaceAll(" ", "-") + ".md";

            File dir = new File("posts");
            if (!dir.exists()) dir.mkdirs();

            Path filePath = Paths.get("posts", seoFileName);
            Files.write(filePath, mdContent.toString().getBytes(StandardCharsets.UTF_8));

            System.out.println("✅ Markdown File Created: " + filePath.toAbsolutePath());
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}