package com.noke.nokemobilelibrary;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

/**
 * Created by Spencer on 6/22/18.
 */

public class NokeMobileApiClient {

    public static String POST(String urlStr, String jsonString, String apiKey)
    {
        HttpURLConnection conn;
        InputStream inputStream;
        String result = "";
        System.setProperty("http.keepAlive", "false");

        try {

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            //Create the SSL connection
            SSLContext sc;
            sc = SSLContext.getInstance("TLS");
            sc.init(null, null, new java.security.SecureRandom());

            int versionCode = BuildConfig.VERSION_CODE;
            String versionName = BuildConfig.VERSION_NAME;

            //set Timeout and method
            conn.setReadTimeout(60000);
            conn.setConnectTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("charset", "utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);


            DataOutputStream wr = new DataOutputStream( conn.getOutputStream());
            wr.writeBytes(jsonString);
            wr.flush();
            wr.close();

            // Add any data you wish to post here

            conn.connect();
            inputStream = conn.getInputStream();

            if (inputStream != null)
            {
                result = convertInputStreamToString(inputStream);
            }
            else{
                result = "Did not work!";
            }


        } catch (IOException |NoSuchAlgorithmException |KeyManagementException e) {
            e.printStackTrace();
        }

        return result;    }

    private static String convertInputStreamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        String result = "";
        while ((line = bufferedReader.readLine()) != null)
            result += line;
        inputStream.close();
        return result;
    }
}
