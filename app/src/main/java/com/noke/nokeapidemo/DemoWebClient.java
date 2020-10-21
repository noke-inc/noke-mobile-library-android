package com.noke.nokeapidemo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.noke.nokemobilelibrary.NokeDevice;

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
 * Created by Spencer on 2/7/18.
 * Demo Web Client for making requests to demo server and show API implementation
 *
 */

class DemoWebClient {

    private final static String TAG = DemoWebClient.class.getSimpleName();
    private String serverUrl = "CLIENT_URL_HERE";
    private DemoWebClientCallback mDemoWebClientCallback;

    private static String POST(String urlStr, JSONObject jsonObject)
    {
        HttpURLConnection conn;
        InputStream inputStream;
        String result = "";
        System.setProperty("http.keepAlive", "false");
        Log.w("CLIENT", "JSON: " + jsonObject);

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();

            //Create the SSL connection
            SSLContext sc;
            sc = SSLContext.getInstance("TLS");
            sc.init(null, null, new java.security.SecureRandom());

            //set Timeout and method
            conn.setReadTimeout(60000);
            conn.setConnectTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("charset", "utf-8");


            DataOutputStream wr = new DataOutputStream( conn.getOutputStream());
            wr.writeBytes(jsonObject.toString());
            wr.flush();
            wr.close();

            // Add any data you wish to post here
            conn.connect();
            inputStream = conn.getInputStream();

            if (inputStream != null) {
                result = convertInputStreamToString(inputStream);
            }
            else{
                result = "Did not work!";
            }


        } catch (IOException |NoSuchAlgorithmException |KeyManagementException e) {
            e.printStackTrace();
        }

        return result;
    }

    private static String convertInputStreamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        StringBuilder result = new StringBuilder();
        while ((line = bufferedReader.readLine()) != null) {
            result.append(line);
        }
        inputStream.close();
        return result.toString();
    }

    void requestUnlock(final NokeDevice noke, final String email)
    {
        /* Note: This is an example request to a demo server, it does not represent a request to the Noke Core API.
         * Requests should not be made to the Core API directly from the mobile app.
         * Please refer to the documentation for more details
         * (https://github.com/noke-inc/noke-mobile-library-android#nok%C4%93-mobile-library-for-android)
         */

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {

                    JSONObject jsonObject = new JSONObject();

                    jsonObject.accumulate("session", noke.getSession());
                    jsonObject.accumulate("mac", noke.getMac());
                    String url = serverUrl + "unlock/";

                    Log.w(TAG, "JSON: " + jsonObject.toString());

                    mDemoWebClientCallback.onUnlockReceived(POST(url, jsonObject), noke);


                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
    }

    void requestUnshackle(final NokeDevice noke, final String email)
    {
        /* Note: This is an example request to a demo server, it does not represent a request to the Noke Core API.
         * Requests should not be made to the Core API directly from the mobile app.
         * Please refer to the documentation for more details
         * (https://github.com/noke-inc/noke-mobile-library-android#nok%C4%93-mobile-library-for-android)
         */

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {

                    JSONObject jsonObject = new JSONObject();

                    jsonObject.accumulate("session", noke.getSession());
                    jsonObject.accumulate("mac", noke.getMac());
                    String url = serverUrl + "unshackle/";

                    mDemoWebClientCallback.onUnlockReceived(POST(url, jsonObject), noke);


                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
    }

    void requestFobSync(final NokeDevice noke)
    {
        /* Note: This is an example request to a demo server, it does not represent a request to the Noke Core API.
         * Requests should not be made to the Core API directly from the mobile app.
         * Please refer to the documentation for more details
         * (https://github.com/noke-inc/noke-mobile-library-android#nok%C4%93-mobile-library-for-android)
         */

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {

                    JSONObject jsonObject = new JSONObject();

                    jsonObject.accumulate("session", noke.getSession());
                    jsonObject.accumulate("mac", noke.getMac());
                    String url = serverUrl + "fobs/sync/";

                    mDemoWebClientCallback.onUnlockReceived(POST(url, jsonObject), noke);


                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
    }



    void setWebClientCallback(DemoWebClientCallback callback){
        this.mDemoWebClientCallback = callback;
    }

    public interface DemoWebClientCallback  {

        void onUnlockReceived(String response, NokeDevice noke);
    }
}


