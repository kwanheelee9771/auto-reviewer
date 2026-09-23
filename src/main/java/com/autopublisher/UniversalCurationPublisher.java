package com.autopublisher;

import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class UniversalCurationPublisher {

    // ===== [API 키 설정 영역] 여기에 발급받은 키를 입력하세요 =====
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");

    private static final String CP_ACCESS_KEY = "쿠팡_액세스키";
    private static final String CP_SECRET_KEY = "쿠팡_시크릿키";

    // [수정된 부분] 알리익스프레스 App Secret 변수 추가
    private static final String ALI_APP_KEY = System.getenv("ALI_APP_KEY") != null ? System.getenv("ALI_APP_KEY").trim() : "";
    private static final String ALI_APP_SECRET = System.getenv("ALI_APP_SECRET") != null ? System.getenv("ALI_APP_SECRET").trim() : "";
    private static final String ALI_TRACKING_ID = System.getenv("ALI_TRACKING_ID") != null ? System.getenv("ALI_TRACKING_ID").trim() : "";

    // =========================================================

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build();

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
        List<Product> searchProducts(String keyword, int limit) throws Exception;
    }

    // --- [1] 네이버 쇼핑 미끼 (차단 우회 및 예외처리 적용) ---
    static class NaverDecoyProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(String keyword, int limit) {
            List<Product> list = new ArrayList<>();
            try {
                // 이중 인코딩 방지를 위해 Jsoup.data()로 파라미터 전달
                String url = "https://search.shopping.naver.com/search/all";

                Document doc = Jsoup.connect(url)
                        .data("query", keyword)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .get();

                Elements allItems = doc.select(".product_item__MDtDF");
                int actualLimit = Math.min(allItems.size(), limit);
                for (int i = 0; i < actualLimit; i++) {
                    Element item = allItems.get(i);
                    String name = item.select(".product_title__Mmw2K").text();
                    String price = item.select(".price_num__S2p_v").text().replaceAll("[^0-9]", "");
                    list.add(new Product(name, price, "https://search.shopping.naver.com", "네이버쇼핑(참고)"));
                }

                // 만약 네이버가 태그명을 변경해 값을 못 가져왔을 경우 안전망(Fallback)
                if (list.isEmpty()) {
                    list.add(new Product("[참고] " + keyword + " 인기상품", "25000", "https://search.shopping.naver.com", "네이버쇼핑"));
                }
            } catch (Exception e) {
                System.out.println("⚠️ 네이버 접근 차단됨 (무시하고 알리 데이터로 넘어갑니다): " + e.getMessage());
                // 방화벽에 막혀도 파이프라인이 멈추지 않도록 기본 참고 데이터 반환
                list.add(new Product("[참고] " + keyword + " 일반상품", "25000", "https://search.shopping.naver.com", "네이버쇼핑"));
            }
            return list;
        }
    }

    // --- [2] 쿠팡 파트너스 API ---
    static class CoupangProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(String keyword, int limit) throws Exception {
            List<Product> list = new ArrayList<>();
            // (이전 답변에서 드린 쿠팡 HMAC 서명 및 호출 로직을 여기에 그대로 넣으시면 됩니다. 테스트를 위해 임시로 1개 반환)
            list.add(new Product("[쿠팡 추천] " + keyword, "45000", "https://coupa.ng/xxxx", "쿠팡"));
            return list;
        }
    }

    // --- [3] 알리익스프레스 API ---
    static class AliExpressProvider implements AffiliateProvider {
        @Override
        public List<Product> searchProducts(String keyword, int limit) throws Exception {
            List<Product> list = new ArrayList<>();
            int pageNo = 1;
            int maxPages = 5; // 최대 5페이지(약 200개 상품)까지만 탐색하는 안전장치

            // 5개가 다 채워지거나, 최대 페이지에 도달할 때까지 반복
            while (list.size() < limit && pageNo <= maxPages) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8")); // 알리 싱가포르 게이트웨이 기준 시간대 설정
                String timestamp = sdf.format(new java.util.Date());

                System.out.println("🔍 [DEBUG] 생성된 타임스탬프: " + timestamp);

                // 1. 파라미터 구성
                java.util.Map<String, String> params = new java.util.TreeMap<>();
                params.put("method", "aliexpress.affiliate.product.query");
                params.put("app_key", ALI_APP_KEY);
                params.put("sign_method", "md5");
                params.put("timestamp", timestamp);
                params.put("format", "json");
                params.put("v", "2.0");
                params.put("keywords", keyword);
                params.put("target_language", "KR");
                params.put("target_currency", "KRW");
                params.put("min_sale_price", "100000");
                params.put("category_ids", "6");
                params.put("tracking_id", ALI_TRACKING_ID);
                params.put("page_size", "40"); // 알리 최대 허용치 고정
                params.put("page_no", String.valueOf(pageNo)); // 페이지 번호 동적 할당

                // 2. MD5 암호화 서명 생성 (페이지 번호가 바뀌므로 매 루프마다 새로 생성해야 함)
                StringBuilder signStr = new StringBuilder(ALI_APP_SECRET);

                System.out.println("🔍 [DEBUG] 서명 생성 원본 문자열: " + signStr.toString());

                for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                    signStr.append(entry.getKey()).append(entry.getValue());
                }
                signStr.append(ALI_APP_SECRET);

                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(signStr.toString().getBytes("UTF-8"));
                StringBuilder signHex = new StringBuilder();
                for (byte b : digest) { signHex.append(String.format("%02X", b)); }

                params.put("sign", signHex.toString());

                // 3. API 호출
                HttpUrl.Builder urlBuilder = HttpUrl.parse("https://api-sg.aliexpress.com/sync").newBuilder();
                for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                    urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
                }

                Request request = new Request.Builder().url(urlBuilder.build()).get().build();

                // 4. 응답 파싱 및 필터링
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) break;

                    String responseBody = response.body().string();
                    JsonObject res = JsonParser.parseString(responseBody).getAsJsonObject();

                    System.out.println("🔍 [디버그] 알리 API 응답 코드: " + response.code());
                    System.out.println("🔍 [디버그] 알리 API 응답 원문: " + responseBody);

                    if (!response.isSuccessful()) {
                        System.out.println("❌ 응답 실패로 인한 탈출");
                        break;
                    }

                    if (res.has("aliexpress_affiliate_product_query_response")) {
                        JsonObject queryRes = res.getAsJsonObject("aliexpress_affiliate_product_query_response");
                        if (queryRes.getAsJsonObject("resp_result").get("resp_code").getAsInt() == 200) {
                            com.google.gson.JsonArray items = queryRes.getAsJsonObject("resp_result").getAsJsonObject("result").getAsJsonObject("products").getAsJsonArray("product");

                            for (int i = 0; i < items.size(); i++) {
                                if (list.size() >= limit) break; // 5개를 채우면 즉시 안쪽 루프 탈출

                                JsonObject item = items.get(i).getAsJsonObject();
                                String title = item.get("product_title").getAsString();
                                String titleLower = title.toLowerCase();

                                if (!titleLower.contains("purifier") && !titleLower.contains("cleaner")) {
                                    continue;
                                }

                                // [강력한 금지어 필터링]
                                if (titleLower.contains("filter") || titleLower.contains("replacement") ||
                                        titleLower.contains("part") || titleLower.contains("accessory") ||
                                        titleLower.contains("diffuser") || titleLower.contains("aroma") ||
                                        titleLower.contains("humidifier") || titleLower.contains("essential") ||
                                        titleLower.contains("perfume") || titleLower.contains("water") ||
                                        titleLower.contains("dispenser") || titleLower.contains("ozone") ||
                                        titleLower.contains("generator") || titleLower.contains("exhaust") ||
                                        titleLower.contains("extractor") || titleLower.contains("vacuum") ||
                                        titleLower.contains("robot") || titleLower.contains("mop") ||
                                        titleLower.contains("sweep") || titleLower.contains("cooler") ||
                                        titleLower.contains("cooling") || titleLower.contains("heater") ||
                                        titleLower.contains("refrigerator") || titleLower.contains("module") ||
                                        titleLower.contains("frame") || titleLower.contains("adapter") ||
                                        titleLower.contains("conditioner")) {
                                    continue;
                                }

                                list.add(new Product(
                                        title,
                                        item.get("target_sale_price").getAsString(),
                                        item.get("promotion_link").getAsString(),
                                        "알리익스프레스"
                                ));
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ 알리 API 에러: " + e.getMessage());
                    break;
                }

                // 이번 페이지에서 5개를 다 못 채웠다면 다음 페이지로 넘어가서 계속 검색
                pageNo++;
            }
            return list;
        }
    }

    private static String generateAiReview(String keyword, List<Product> products) throws Exception {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            return "⚠️ OpenAI API 키가 설정되지 않아 임시 텍스트를 출력합니다.";
        }

        // 1. 프롬프트(Prompt) 조립
        StringBuilder prompt = new StringBuilder();
        prompt.append("너는 IT 가전제품 및 생활용품 전문 리뷰어이자 파워블로거야. ");
        prompt.append("다음 수집된 '").append(keyword).append("' 상품 5개의 정보를 바탕으로, 독자가 구매하고 싶게 만드는 매력적인 비교 추천 글을 작성해줘.\n\n");

        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            prompt.append(i + 1).append(". ").append(p.name).append(" (가격: ").append(p.price).append("원)\n");
        }

        prompt.append("\n[조건]\n");
        prompt.append("- 서론: ").append(keyword).append(" 구매 시 고려해야 할 핵심 가이드 2~3줄\n");
        prompt.append("- 본론: 각 상품별 특징을 2~3문장으로 자연스럽게 소개 (마크다운 불릿 포인트 사용)\n");
        prompt.append("- 결론: 어떤 사람에게 어떤 제품이 맞는지 최종 요약\n");
        prompt.append("- 말투: 전문적이면서도 친근한 '~해요', '~입니다' 체\n");
        prompt.append("- 주의: 구매 링크나 URL은 본문에 직접 넣지 말 것 (시스템이 하단에 자동 첨부함)\n");

        // 2. JSON 페이로드 생성 (Gson 활용)
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt.toString());

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-4o-mini"); // 가성비가 좋고 빠른 최신 모델
        jsonBody.add("messages", messages);
        jsonBody.addProperty("temperature", 0.7);

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(mediaType, jsonBody.toString());

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .post(body)
                .build();

        // 3. API 요청 및 응답 파싱
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI API 에러: " + response.code() + " " + response.body().string());
            }
            String resBody = response.body().string();
            JsonObject resJson = JsonParser.parseString(resBody).getAsJsonObject();
            return resJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        }
    }

    // --- [메인 실행부 : 파일 생성 로직] ---
    public static void main(String[] args) {
        System.setProperty("https.protocols", "TLSv1.2");

        String aliSearchKeyword = "air purifier";
        String postTitleKeyword = "공기청정기";
        System.out.println("작업 키워드: " + postTitleKeyword + " (알리 검색: " + aliSearchKeyword + ")");

        try {
            // 1. 제휴사 데이터 소싱
            List<AffiliateProvider> providers = new ArrayList<>();
            //providers.add(new NaverDecoyProvider());
            //providers.add(new CoupangProvider());
            providers.add(new AliExpressProvider());

            List<Product> curatedProducts = new ArrayList<>();
            for (AffiliateProvider provider : providers) {
                curatedProducts.addAll(provider.searchProducts(aliSearchKeyword, 5));
            }

            System.out.println("📦 수집된 유효 상품 수: " + curatedProducts.size() + "개");

            // 🛑 [추가] 수집된 상품이 0개이면 OpenAI를 호출하지 않고 즉시 중단
            if (curatedProducts.isEmpty()) {
                System.err.println("❌ 수집된 상품이 없어 AI 리뷰 생성을 중단합니다. (알리 API 응답 확인 필요)");
                System.exit(1); // 빌드 실패 처리 또는 강제 종료
            }

            // 2. AI 글 작성 (임시 더미 텍스트)
            //System.out.println("AI 비교 리뷰 생성 중...");
            //String aiReview = "이 포스팅은 AI가 분석한 원룸 소형 공기청정기 장단점 비교글입니다.\n\n각 제품의 스펙과 가성비를 중점적으로 비교했습니다.";

            System.out.println("OpenAI API를 통해 비교 리뷰 원고 생성 중...");
            String aiReview = generateAiReview(postTitleKeyword, curatedProducts);

            // 3. 마크다운 본문 조립 (SSG 규격에 맞춘 YAML Frontmatter 추가)
            Date now = new Date();
            String dateFormatted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(now);

            StringBuilder mdContent = new StringBuilder();
            mdContent.append("---\n");
            mdContent.append("title: \"").append(postTitleKeyword).append(" 가성비 추천 BEST 5\"\n");
            mdContent.append("date: ").append(dateFormatted).append("\n");
            mdContent.append("category: \"가전제품\"\n");
            mdContent.append("tags: [\"").append(postTitleKeyword).append("\", \"가성비\", \"추천\"]\n");
            mdContent.append("---\n\n");

            mdContent.append(aiReview).append("\n\n");
            mdContent.append("---\n### 🛒 최저가 구매 좌표 (실시간 변동 가능)\n\n");

            for (Product p : curatedProducts) {
                // 마크다운 링크 문법 [텍스트](URL) 사용
                mdContent.append(String.format("- **[%s]** %s - [%s원 할인가 확인하기](%s)\n",
                        p.source, p.name, p.price, p.url));
            }
            mdContent.append("\n<br><span style='font-size:12px; color:#888;'>*이 포스팅은 제휴마케팅 활동의 일환으로 일정액의 수수료를 제공받습니다.</span>\n");

            // 4. 로컬에 .md 파일로 저장
            String fileDatePrefix = new SimpleDateFormat("yyyy-MM-dd").format(now);
            String seoFileName = fileDatePrefix + "-" + postTitleKeyword.replaceAll(" ", "-") + ".md";

            File dir = new File("posts");
            if (!dir.exists()) dir.mkdirs(); // posts 폴더가 없으면 생성

            Path filePath = Paths.get("posts", seoFileName);
            Files.write(filePath, mdContent.toString().getBytes(StandardCharsets.UTF_8));

            System.out.println("✅ 로컬 파일 생성 완료: " + filePath.toAbsolutePath());
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}