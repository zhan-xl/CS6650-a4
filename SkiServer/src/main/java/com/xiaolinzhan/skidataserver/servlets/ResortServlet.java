package com.xiaolinzhan.skidataserver.servlets;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.redisson.api.RBucket;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

import org.redisson.api.RedissonClient;

@WebServlet(name = "resort servlet", value = "/resorts/*")
public class ResortServlet extends HttpServlet {

  private MongoClient mongoClient;

  private RedissonClient redissonClient;

  public void init(ServletConfig config) throws ServletException {
    super.init(config);

    mongoClient = (MongoClient) getServletContext().getAttribute("MongoClient");

    redissonClient = (RedissonClient) getServletContext().getAttribute("redis");
  }

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");

    String urlPath = request.getPathInfo();
    if (urlPath == null || urlPath.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      JsonObject responseJson = new JsonObject();
      responseJson.addProperty("message", "invalid request.");
      response.getWriter().write(responseJson.toString());
      return;
    }
    // url validation + data validation
    String[] urlParas = urlPath.split("/");

    if (urlParas.length != 7
        || !Objects.equals(urlParas[2], "seasons")
        || !Objects.equals(urlParas[4], "day") || !Objects.equals(urlParas[6], "skiers")
        || !isInteger(urlParas[1]) || !isInteger(urlParas[3]) || !isInteger(urlParas[5])) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      JsonObject responseJson = new JsonObject();
      responseJson.addProperty("message", "invalid request.");
      response.getWriter().write(responseJson.toString());
      return;
    }
    int resortID = Integer.parseInt(urlParas[1]);
    String seasonID = urlParas[3];
    String dayID = urlParas[5];
    // check cache first

    // generate cache key
    String cacheKey = "resort: " + resortID + " season: " + seasonID + " day: " + dayID;
    // Check if the data exists in the Redis cache
    RBucket<Object> bucket = redissonClient.getBucket(cacheKey);
    if (bucket.isExists()) {
      Map<String, Object> cacheData = (Map<String, Object>) bucket.get();

      if (cacheData != null &&
          cacheData.containsKey("skiersCount") &&
          cacheData.containsKey("timestamp") &&
          cacheData.containsKey("cacheExpiration")
      ) {
        int skiersCount = (int) cacheData.get("skiersCount");
        long timestamp = (long) cacheData.get("timestamp");

        long currentTime = System.currentTimeMillis() / 1000;
        long cacheExpiration = (long) cacheData.get("cacheExpiration");

        if (currentTime - timestamp <= cacheExpiration) {
          // time stamp check if exceed the time threshold, query from DB to get most up-to-date result
          response.setStatus(HttpServletResponse.SC_OK);
          Gson gson = new Gson();
          String jsonValue = gson.toJson(skiersCount);
          response.getWriter().write(jsonValue);
          return;
        }
      }
    }
    // if not exist in cache or expired, query DB - get number of unique skiers at resort/season/day
    int numberOfSkiers = getSkiersNumberAndUpdateCache(bucket, resortID, seasonID, dayID);

    response.setStatus(HttpServletResponse.SC_OK);
    Gson gson = new Gson();
    String jsonValue = gson.toJson(numberOfSkiers);
    response.getWriter().write(jsonValue);
  }

  private int getSkiersNumberAndUpdateCache(RBucket<Object> bucket, int resortID,
      String seasonID, String dayID) {
    Bson projectionFields = Projections.fields(Projections.include("skierID"),
        Projections.excludeId());
    Bson filter = Filters.and(Filters.eq("resortID", resortID),
        Filters.eq("seasonID", seasonID), Filters.eq("dayID", dayID));
    MongoCursor<Document> cursor = mongoClient.getDatabase("SkiDatabase").getCollection("liftRides")
        .find(filter).projection(projectionFields).iterator();
    HashSet<Integer> skiers = new HashSet<>();
    while (cursor.hasNext()) {
      Integer skierID = cursor.next().getInteger("skierID");
      skiers.add(skierID);
    }

    int numberOfSkiers = skiers.size();

    // Store the result in cache for future requests
    long currentTime = System.currentTimeMillis() / 1000;

    Map<String, Object> cachestore = new HashMap<>();
    cachestore.put("skiersCount", numberOfSkiers);
    cachestore.put("timestamp", currentTime);
    Duration cacheDuration = Duration.ofDays(1);
    long absoluteExpiration = currentTime + cacheDuration.getSeconds();
    cachestore.put("cacheExpiration", absoluteExpiration);
    bucket.set(cachestore); // redisson serializes to JSON

    bucket.expire(cacheDuration);

    return numberOfSkiers;
  }

  private static boolean isInteger(String str) {
    try {
      Integer.parseInt(str);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

}
