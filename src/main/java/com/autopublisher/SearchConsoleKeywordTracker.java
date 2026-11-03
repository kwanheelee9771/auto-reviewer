package com.autopublisher;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SearchConsoleKeywordTracker {

    // [필수] 서치콘솔 등록 URL (URL 접두사면 맨 끝 / 포함, 도메인 속성이면 sc-domain:도메인)
    private static final String SITE_URL = "https://tech-curator.info/";
    private static final String GCP_KEY_JSON = System.getenv("GCP_KEY_JSON");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * GCP 서비스 계정 JSON으로 Google OAuth2 Access Token 발급
     */
    private static String getAccessToken() throws Exception {
        if (GCP_KEY_JSON == null || GCP_KEY_JSON.trim().isEmpty()) {
            throw new IllegalStateException("GCP_KEY_JSON 환경 변수가 없습니다.");
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(GCP_KEY_JSON.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/webmasters.readonly"));

        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    /**
     * 서치콘솔에서 기회 키워드(클릭 0, 노출 8회 이상, 게재순위 8~25위) 추출
     */
    public static List<String> fetchOpportunityKeywords() {
        List<String> targetKeywords = new ArrayList<>();

        try {
            String accessToken = getAccessToken();

            // 서치콘솔 API 엔드포인트
            String encodedSiteUrl = URLEncoder.encode(SITE_URL, "UTF-8");
            String apiUrl = "https://www.googleapis.com/webmasters/v3/sites/" + encodedSiteUrl + "/searchAnalytics/query";

            // 최근 14일 전 ~ 3일 전 기간 설정
            LocalDate endDate = LocalDate.now().minusDays(3);
            LocalDate startDate = endDate.minusDays(14);

            // 요청 바디 생성 (query 기준 그룹핑)
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("startDate", startDate.toString());
            requestBody.addProperty("endDate", endDate.toString());
            requestBody.addProperty("rowLimit", 100);

            JsonArray dimensions = new JsonArray();
            dimensions.add("query");
            requestBody.add("dimensions", dimensions);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(MediaType.parse("application/json"), requestBody.toString()))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject res = JsonParser.parseString(response.body().string()).getAsJsonObject();

                    if (res.has("rows") && !res.get("rows").isJsonNull()) {
                        JsonArray rows = res.getAsJsonArray("rows");

                        for (JsonElement el : rows) {
                            JsonObject row = el.getAsJsonObject();
                            String query = row.getAsJsonArray("keys").get(0).getAsString();
                            double clicks = row.has("clicks") ? row.get("clicks").getAsDouble() : 0.0;
                            double impressions = row.has("impressions") ? row.get("impressions").getAsDouble() : 0.0;
                            double position = row.has("position") ? row.get("position").getAsDouble() : 0.0;

                            // 기회 키워드 필터링 조건
                            if (clicks == 0 && impressions >= 8 && position >= 8.0 && position <= 25.0) {
                                if (!query.contains("yoga") && !query.contains("organic") && !query.contains("sleeper")) {
                                    targetKeywords.add(query);
                                    System.out.println(String.format("🎯 기회 키워드 포착: [%s] | 노출: %.0f | 클릭: %.0f | 평균순위: %.1f",
                                            query, impressions, clicks, position));
                                }
                            }
                        }
                    } else {
                        System.out.println("⚠️ 서치콘솔 집계 행(rows)이 비어 있습니다.");
                    }
                } else {
                    System.out.println("❌ 서치콘솔 API 응답 실패 코드: " + response.code());
                    if (response.body() != null) {
                        System.out.println("❌ 에러 본문: " + response.body().string());
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("❌ 서치콘솔 키워드 추적 실패: " + e.getMessage());
        }

        return targetKeywords;
    }

    public static void main(String[] args) {
        System.out.println("🚀 서치콘솔 API 연동 테스트 시작...");
        List<String> list = fetchOpportunityKeywords();
        System.out.println("✅ 결과 수집 완료. 발견된 키워드: " + list);
    }
}