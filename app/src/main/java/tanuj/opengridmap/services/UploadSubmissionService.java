package tanuj.opengridmap.services;

import android.accounts.Account;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.plus.Plus;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import tanuj.opengridmap.BuildConfig;
import tanuj.opengridmap.R;
import tanuj.opengridmap.data.OpenGridMapDbHelper;
import tanuj.opengridmap.models.Submission;

public class UploadSubmissionService extends IntentService implements Callback,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        private static final String TAG = UploadSubmissionService.class.getSimpleName();

    public static final String UPLOAD_UPDATE_BROADCAST = BuildConfig.APPLICATION_ID + ".upload.update";

    public static final int UPLOAD_STATUS_FAIL = -1;

    public static final int UPLOAD_STATUS_SUCCESS = 100;

    private static final int MAX_UPLOAD_ATTEMPTS = 1;

    private static final String WEB_CLIENT_ID = "498377614550-0q8d0e0fott6qm0rvgovd4o04f8krhdb.apps.googleusercontent.com";

    private static final String SERVER_BASE_URL = "http://vmjacobsen39.informatik.tu-muenchen.de";

    private static final String SUBMISSION_URL = SERVER_BASE_URL + "/submissions/create";

    private static final String ACTION_UPLOAD = BuildConfig.APPLICATION_ID + ".services.action.upload";

    private static final String EXTRA_SUBMISSION_ID = BuildConfig.APPLICATION_ID + ".services.extra.upload_queue_item";

    private GoogleApiClient googleApiClient;

    private String idToken;

    private long submissionId;

    public UploadSubmissionService() {
        super("UploadSubmissionService");
    }

    public static void startUpload(Context context, long submissionId) {
        Intent intent = new Intent(context, UploadSubmissionService.class);
        intent.setAction(ACTION_UPLOAD);
        intent.putExtra(EXTRA_SUBMISSION_ID, submissionId);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_UPLOAD.equals(action)) {
                submissionId = intent.getLongExtra(EXTRA_SUBMISSION_ID, -1);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.v(TAG, "Upload Service OnCreate");
        googleApiClient = buildGoogleApiClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!googleApiClient.isConnected()) {
            googleApiClient.connect();
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (googleApiClient.isConnected()) {
            googleApiClient.disconnect();
            Log.d(TAG, "Disconnected from Google Play Services");
        }

        Log.v(TAG, "Stopping " + TAG);
        super.onDestroy();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.v(TAG, "Connected to Google Play Services");
        new GetIdTokenTask().execute();
    }

    @Override
    public void onConnectionSuspended(int i) {
        googleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        int errorCode = connectionResult.getErrorCode();
        Log.e(TAG, "onConnectionFailed: ErrorCode : " + errorCode);

        if (errorCode == ConnectionResult.API_UNAVAILABLE) {
            Log.v(TAG, "API Unavailable");
        }
    }

    private GoogleApiClient buildGoogleApiClient() {
        GoogleApiClient.Builder builder = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Plus.API, Plus.PlusOptions.builder().build())
                .addScope(Plus.SCOPE_PLUS_LOGIN);

        return builder.build();
    }

    private void handleUpload() {
        OpenGridMapDbHelper dbHelper = new OpenGridMapDbHelper(getApplicationContext());

        Submission submission = dbHelper.getSubmission(submissionId);
        if (submission == null) return;

        final String json = submission.getUploadPayload(getApplicationContext(), idToken, 0)
                .getPayloadEntity();

        try {
            handlePayload(json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void handlePayload(final String json) throws IOException {
        OkHttpClient client = new OkHttpClient();
//        client.setConnectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
//        client.setReadTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        client.interceptors().add(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();

                // try the request
                Response response = chain.proceed(request);

                int tryCount = 1;
                while (!response.isSuccessful() && tryCount < MAX_UPLOAD_ATTEMPTS) {
                    Log.d(TAG, "Upload attempt " + tryCount + " not successful - " + tryCount);

                    tryCount++;

                    // retry the request
                    response = chain.proceed(request);
                }

                // otherwise just pass the original response on
                return response;
            }
        });

        final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

        RequestBody body = RequestBody.create(JSON, json);

        String SERVER_BASE_URL = "http://vmjacobsen39.informatik.tu-muenchen.de";

        String SUBMISSION_URL = SERVER_BASE_URL + "/submissions/create";

        final Request request = new Request.Builder()
                .header("Content-Type", "application/json")
                .url(SUBMISSION_URL)
                .post(body)
                .build();

//        Response response = client.newCall(request).execute();

        client.newCall(request).enqueue(this);
    }

    @Override
    public void onFailure(Request request, IOException e) {
        Log.e(TAG, "Fail");
        e.printStackTrace();
        broadcastUpdate(UPLOAD_STATUS_FAIL);
    }

    @Override
    public void onResponse(Response response) throws IOException {
        String responseStr = response.body().string();
        Log.d(TAG, responseStr);

        try {
            JSONObject responseJSON = new JSONObject(responseStr);

            if (responseJSON.has(getString(R.string.response_key_status)) &&
                    responseJSON.getString(getString(R.string.response_key_status))
                            .equals(getString(R.string.response_status_ok))) {
                    broadcastUpdate(UPLOAD_STATUS_SUCCESS);
            } else {
                broadcastUpdate(UPLOAD_STATUS_FAIL);
            }
        } catch (JSONException e) {
            broadcastUpdate(UPLOAD_STATUS_FAIL);
            e.printStackTrace();
        }
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    private void broadcastUpdate(int uploadCompletion) {
        Log.v(TAG, "Upload Completion for Submission " + submissionId + " : " + uploadCompletion);

        Intent intent = new Intent(UPLOAD_UPDATE_BROADCAST);
        intent.putExtra(getString(R.string.key_submission_id), submissionId);
        intent.putExtra(getString(R.string.key_upload_completion), uploadCompletion);

        sendBroadcast(intent);

    }

    private class GetIdTokenTask extends AsyncTask<Void, Void, Void> {
        private String idToken;

        @Override
        protected Void doInBackground(Void... params) {
            idToken = getIdToken();

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            setIdToken(idToken);
            handleUpload();
        }

        private String getIdToken() {
            String accountName = Plus.AccountApi.getAccountName(googleApiClient);
            Account account = new Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
            String scopes = "audience:server:client_id:" + WEB_CLIENT_ID;
            String token = null;
            final Context context = getApplicationContext();

            try {
                token = GoogleAuthUtil.getToken(context, account, scopes);
            } catch (UserRecoverableAuthException e) {
                e.printStackTrace();
                Log.e(TAG, "Error Retrieving ID Token : " + e);
            } catch (GoogleAuthException e) {
                e.printStackTrace();
                Log.e(TAG, "Error Retrieving ID Token : " + e);
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "Error Retrieving ID Token : " + e);
            }
            return token;
        }
    }
}
