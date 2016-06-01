package com.connectordb_android.logger;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Handler;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessStatusCodes;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;

import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * The GoogleFitLogger handles all aspects of connecting to google's fitness API and
 * managing all relevant datapoints. Any class that wants to gather from the fitness api
 * can extend this class to make logging not so much of a PITA.
 *
 * The main difference between logging data normally and through the google fitness API is
 * that the fitness API actually handles all aspects of logging data in the background for us.
 * This means that all data is cached by google (it also means that google is getting a free copy
 * of the data, but face it, google already owns you anyways).
 * All we need to do is read the gathered data once in a while, and put it into our cache, so that it will be synced
 * to ConnectorDB.
 */
public abstract class GoogleFitLogger extends BaseLogger implements GoogleApiSingleton.ApiCallback, ResultCallback<Status> {

    /**
     * handleDatapoint is called during synchronization with the google fit API. It is your job to insert each datapoint into
     * the cache if you want it there. Datapoints are assumed to be ordered
     *
     * @param db the SQLite database to use for writing datapoints - use db for writing data, since it performs a transaction.
     * @param dp the datapoint that was returned.
     */
    public abstract void handleDatapoint(SQLiteDatabase db, DataPoint dp);


    /**
     * The GoogleFitLogger uses one background process to handle ALL google fit subscriptions,
     * so it synchronizes all at the same time, rather than wasting battery synchronizing one
     * at a time at different times.
     */

    protected GoogleApiClient googleApiClient;
    protected DataType dataType;
    protected int logtime = 0;

    // This is used to trigger sync every time period
    private Handler handler = new Handler();
    private boolean syncIsRunning = false;

    /**
     * sync takes the data that the fit API gathered for us, and moves it into our data cache,
     * so that it will be inserted into ConnectorDB next time the cache is synced. This step happens in the background.
     */
    private synchronized boolean sync() {
        if (googleApiClient==null || !googleApiClient.isConnected()) {
            warn("Google API is not connected - can't sync fit");
            return false;
        }

        log("Syncing Fit");

        // We need to set up the time ranges to query from google's history
        long endTime = timestamp();

        // We keep track of the start time so we don't query for unnecessary datapoints in the future.
        long startTime = 1;
        try {
            startTime = Long.parseLong(kvGet("fit_startTime"));
        } catch(NumberFormatException nfe) {}

        log("FitSync: Start Time: "+ Long.toString(startTime));

        DataReadRequest readRequest = new DataReadRequest.Builder()
                .read(dataType)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .setLimit(1000)    // Docs say this is the maximum... anything higher times out
                .build();
        DataReadResult dataReadResult =
                Fitness.HistoryApi.readData(googleApiClient, readRequest).await(1, TimeUnit.MINUTES);
        log("Have read result");
        endTime = startTime;
        SQLiteDatabase db = getDB();
        if (!dataReadResult.getStatus().isSuccess()) {
            error(dataReadResult.toString());
        } else {
            List<DataPoint> dplist = dataReadResult.getDataSet(dataType).getDataPoints();
            if (dplist.size()==1000) {
                // The data size is 1000 - this is not cool at all. It means that we got a full dataset!
                // there might be data that we didn't get! We therefore run a backtrack until we get less than
                // 1000 datapoints.
                log("Up to datapoint limit! There might be missing datapoints! TODO: FIX THIS");
                // TODO: perform backtrack to get all data stored in google fit

            }


            db.beginTransactionNonExclusive();
            for (DataPoint dp : dataReadResult.getDataSet(dataType).getDataPoints()) {

                // Let the specific logger handle the datapoint type
                handleDatapoint(db, dp);

                long et = et = dp.getEndTime(TimeUnit.MILLISECONDS);
                if (et > endTime) {
                    endTime = et;
                }
            }
            db.endTransaction();

            kvSet("fit_startTime", Long.toString(endTime));

        }
        log("End sync");

        return true;
    }

    /**
     * Syncer is a Runnable that runs sync in the background, and sets up a repeated sync
     */
    private class Syncer implements Runnable {
        @Override
        public void run() {
            new AsyncTask<Void,Void,Void>() {
                @Override
                protected Void doInBackground(Void ...params) {
                    GoogleFitLogger.this.sync();
                    GoogleFitLogger.this.runSyncer();
                    return null;
                }
            }.execute();
        }
    }

    /**
     * runSyncer sets up the syncer to auto run
     */
    private synchronized void runSyncer() {
        if (logtime == 0) {
            // Once an hour if nothing given
            logtime = 1000*60*60;
        }
        if (logtime > 0) {
            syncIsRunning = true;
            log("Setting up new sync in "+ Integer.toString(logtime)+"ms");
            handler.postDelayed(new Syncer(),logtime);
        } else {
            syncIsRunning = false;
        }
    }

    private void ensureSyncer() {
        if (!syncIsRunning) {
            runSyncer();
        }
    }


    /**
     * GoogleFitLogger enables a logger which uses data fom Google Fit - it abstracts away all the annoyance
     * of handling the synchronization and syncing on your own, and directly gives you the correct stream to
     * process.
     *
     * @param dataType the fitness DataType to gather. Make sure that the correct scope for the datatypes
     *                 is enabled in GoogleApiSingleton, otherwise there will be a permissions error
     */
    GoogleFitLogger(String logName, String streamName, String jsonSchema,String datatype,String icon, Context c,DataType dataType) {
        super(logName,streamName,jsonSchema,datatype,icon,c);
        this.dataType = dataType;


        log("Connecting to Google Play services");
        GoogleApiSingleton.get().getGoogleApi(c, this);

    }

    // connected - enable logging
    @Override
    public void connected(GoogleApiClient g) {
        log("Google play services connected.");
        googleApiClient = g;
        gatherEnabled();
    }

    // disconnected callback from the GoogleApiSingleton
    @Override
    public void disconnected(String reason) {
        warn("Google play services connection failed");
        // well, crap

    }

    //Subscribing to data from fitness API
    @Override
    public void onResult(Status status) {
        if (status.isSuccess()) {
            if (status.getStatusCode()
                    == FitnessStatusCodes.SUCCESS_ALREADY_SUBSCRIBED) {
                log("Existing subscription detected.");
            } else {
                log("Successfully subscribed!");
            }
        } else {
            if (status.getStatusMessage()!=null) {
                error( status.getStatusMessage());
            } else {
                error("subscribe to fit api failed");
            }
        }
    }


    private void gatherEnabled() {

        if (googleApiClient!=null && googleApiClient.isConnected()) {
            if (logtime!=-1) {
                log("Enabling");
                Fitness.RecordingApi.subscribe(googleApiClient, dataType)
                        .setResultCallback(this);
                ensureSyncer();


            } else {
                log("Disabling");
                Fitness.RecordingApi.unsubscribe(googleApiClient, dataType)
                        .setResultCallback(this);
            }
        }
    }

    /**
     * In the fitlogger, you can only turn logging on and off, since google's fitness API actually
     * gathers the data for us - all we control is when we sync to our cache.
     *
     * @param value The time in milliseconds between data updates. -1 means turn off logging
     *              and 0 means "background" - which the individual loggers are free to implement
     */
    @Override
    public void setLogTimer(int value) {
        logtime=value;
        // If not -1, enable gather
        gatherEnabled();
    }

    @Override
    public String getSettingSchema() {
        // So apparently in java, not only don't you NOT have multiline strings, but the only quote character
        // is ", meaning that we need hacks to do basic stuff... I am not a big fan of java.
        return ("{'type': 'object',"+
        "'properties': {"+
                "'timeBetweenSyncs': {'type': 'integer'}"+
        "},'required': ['timeBetweenSyncs']}").replace('\'','"');
    }

    /**
     * Note: We are making the critical assumption that the lifetime of the FitLogger is the same as
     * the lifetime of the full application, since we are not removing this instance from loggers
     */
    @Override
    public void close() {
        log("Closing fit logger");
    }

}