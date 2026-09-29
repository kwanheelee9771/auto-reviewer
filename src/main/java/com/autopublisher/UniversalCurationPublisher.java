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
        String imageUrl;

        public Product(String name, String price, String url, String source, String imageUrl) {
            this.name = name.replaceAll("<[^>]*>", "");
            this.price = price;
            this.url = url;
            this.source = source;
            this.imageUrl = imageUrl;
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
            //list.add(new Product("[쿠팡 추천] " + rule.keyword, "45000", "https://coupa.ng/xxxx", "Coupang"));
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

                                String mainImageUrl = "";
                                if (item.has("product_main_image_url")) {
                                    mainImageUrl = item.get("product_main_image_url").getAsString();
                                }

                                list.add(new Product(
                                        title,
                                        item.get("target_sale_price").getAsString(),
                                        item.get("promotion_link").getAsString(),
                                        "AliExpress",
                                        mainImageUrl
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

        int dayOfYear = LocalDate.now().getDayOfYear();
        int startIndex = dayOfYear % rules.length;

        CategoryRule successfulRule = null;
        List<Product> curatedProducts = new ArrayList<>();

        // [핵심 추가] 상품이 찾아질 때까지 다음 카테고리를 순회하는 로직
        for (int i = 0; i < rules.length; i++) {
            int currentIndex = (startIndex + i) % rules.length;
            CategoryRule targetRule = rules[currentIndex];

            System.out.println("🚀 [" + (i+1) + "차 시도] 카테고리: " + targetRule.keyword + " (최소 ₩" + targetRule.minPriceKrw + ")");

            try {
                List<AffiliateProvider> providers = new ArrayList<>();
                // providers.add(new NaverDecoyProvider());
                // providers.add(new CoupangProvider());
                providers.add(new AliExpressProvider());

                curatedProducts.clear();
                for (AffiliateProvider provider : providers) {
                    curatedProducts.addAll(provider.searchProducts(targetRule, 5));
                }

                if (!curatedProducts.isEmpty()) {
                    successfulRule = targetRule;
                    System.out.println("✅ 상품 찾기 성공! 수집된 상품 수: " + curatedProducts.size());
                    break; // 성공했으므로 반복문 탈출
                } else {
                    System.out.println("⚠️ 해당 카테고리에서 조건에 맞는 상품이 없어 다음 카테고리로 넘어갑니다.");
                    Thread.sleep(2000); // API 호출 속도 제한 방지용 2초 대기
                }
            } catch (Exception e) {
                System.out.println("⚠️ 시도 중 에러 발생: " + e.getMessage());
            }
        }

        if (curatedProducts.isEmpty() || successfulRule == null) {
            System.err.println("❌ 7개 카테고리 모두 상품 검색 실패 (API 오류 추정). 강제 종료합니다.");
            System.exit(1);
        }

        try {
            System.out.println("Generating English Review via OpenAI for " + successfulRule.keyword + "...");
            String aiReview = generateAiReview(successfulRule.keyword, curatedProducts);

            Date now = new Date();
            String dateFormatted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(now);

            StringBuilder mdContent = new StringBuilder();
            mdContent.append("---\n");
            mdContent.append("title: \"Top 5 Best ").append(successfulRule.keyword).append(" (Highly Recommended)\"\n");
            mdContent.append("date: ").append(dateFormatted).append("\n");
            mdContent.append("category: \"Tech Gadgets\"\n");
            mdContent.append("tags: [\"").append(successfulRule.keyword).append("\", \"Best Deals\", \"Review\"]\n");

            if (curatedProducts.get(0).imageUrl != null && !curatedProducts.get(0).imageUrl.isEmpty()) {
                mdContent.append("cover:\n");
                mdContent.append("  image: \"").append(curatedProducts.get(0).imageUrl).append("\"\n");
                mdContent.append("  alt: \"").append(successfulRule.keyword).append("\"\n");
            }
            mdContent.append("---\n\n");

            mdContent.append(aiReview).append("\n\n");

            mdContent.append("---\n### 🛒 Best Deals & Latest Prices\n\n");

            for (int i = 0; i < curatedProducts.size(); i++) {
                Product p = curatedProducts.get(i);
                mdContent.append("#### ").append(i + 1).append(". ").append(p.name).append("\n\n");

                if (p.imageUrl != null && !p.imageUrl.isEmpty()) {
                    mdContent.append("<img src=\"").append(p.imageUrl).append("\" alt=\"").append(p.name.replace("\"", "")).append("\" width=\"400\" style=\"border-radius:8px; margin-bottom:10px;\" />\n\n");
                }
                mdContent.append("👉 **[Check Current Price on ").append(p.source).append("](").append(p.url).append(")**\n\n");
            }

            mdContent.append("---\n<br><span style='font-size:12px; color:#888;'>*Disclosure: This post contains affiliate links. We may earn a commission at no extra cost to you.</span>\n");

            String fileDatePrefix = new SimpleDateFormat("yyyy-MM-dd").format(now);
            String seoFileName = fileDatePrefix + "-" + successfulRule.keyword.toLowerCase().replaceAll(" ", "-") + ".md";

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