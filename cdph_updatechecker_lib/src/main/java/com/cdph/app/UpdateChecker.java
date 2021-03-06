/*
* Checks for updates through json
*
*@author    SnoopyCodeX
*@copyright 2020
*@email     extremeclasherph@gmail.com
*@package   com.cdph.app
*/

package com.cdph.app;

import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;

import com.cdph.app.json.JSONReader;

public final class UpdateChecker
{
	private static OnUpdateDetectedListener listener;
	private static String updateLogUrl = "";
	private static boolean autoRun = false, autoInstall = false, updateOnWifiOnly = true;
	private static JSONReader jsonReader;
	private static Context ctx;
	
	/*
	* For singleton purposes
	*
	*@param        context
	*@constructor  UpdateChecker
	*/
	private UpdateChecker(Context context)
	{
		this.ctx = context;
	}
	
	/*
	* Used to get the instance of this class
	*
	*@param  context
	*@return UpdateChecker.class
	*/
	public static final UpdateChecker getInstance(Context ctx)
	{
		return (new UpdateChecker(ctx));
	}
	
	/*
	* When set to true, this will auto check for
	* new updates when connected to the internet.
	* Default is false.
	*
	*@param	  autoRun             - Default is false
	*@return  UpdateChecker.class
	*/
	public UpdateChecker shouldAutoRun(boolean autoRun)
	{
		this.autoRun = autoRun;
		
		if(autoRun)
			this.ctx.registerReceiver(new ConnectivityReceiver(), new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
			
		return this;
	}
	
	/*
	* The url where the new info of the app
	* will be read to.
	*
	*@param   updateLogsUrl       - The url of the json-encoded info of the new update
	*@return  UpdateChecker.class
	*/
	public UpdateChecker setUpdateLogsUrl(String updateLogsUrl)
	{
		this.updateLogUrl = updateLogsUrl;
		return this;
	}
	
	/*
	* When set to true, this will automatically
	* install the new app after it has been
	* downloaded.
	*
	*@param   autoInstall         - Default is false
	*@return  UpdateChecker.class
	*/
	public UpdateChecker shouldAutoInstall(boolean autoInstall)
	{
		this.autoInstall = autoInstall;
		return this;
	}
	
	/*
	* When set to false, this will also allow
	* updating using mobile data.
	*
	*@param  wifiOnly  - If will only check for updates if connected on wifi network
	*@return UpdateChecker.class
	*/
	public UpdateChecker shouldCheckUpdateOnWifiOnly(boolean wifiOnly)
	{
		this.updateOnWifiOnly = wifiOnly;
		return this;
	}
	
	/*
	* Sets an OnUpdateDetectedListener, when a new update
	* is detected, this listener will be triggered.
	*
	*@param   listener            - The listener to be triggered
	*@return  UpdateChecker.class
	*/
	public UpdateChecker setOnUpdateDetectedListener(UpdateChecker.OnUpdateDetectedListener listener)
	{
		this.listener = listener;
		return this;
	}
	
	/*
	* Sets a custom json reader to suit your needs
	*
	*@param  jsonReader           - A custom class that extends {com.cdph.app.json.JSONReader}
	*@return UpdateChecker.class
	*/
	public <T extends JSONReader> UpdateChecker setJsonReader(T jsonReader)
	{
		this.jsonReader = jsonReader;
		return this;
	}
	
	/*
	* Runs the update checker
	*
	*@return null
	*/
	public void runUpdateChecker()
	{
		try {
			if(ConnectivityReceiver.isConnected(ctx))
				(new TaskUpdateChecker()).execute(updateLogUrl);
			else
				Toast.makeText(ctx, String.format("[ERROR (runUpdateChecker)]: %s", "You are not connected to a wifi network"), Toast.LENGTH_LONG).show();
		} catch(Exception e) {
			e.printStackTrace();
			Toast.makeText(ctx, String.format("[ERROR (runUpdateChecker)]: %s", e.getMessage()), Toast.LENGTH_LONG).show();
		}
	}
	
	/*
	* Installs the application
	*
	*@param	  filePath  - The path of the apk to be installed
	*@return  null
	*/
	public static void installApp(String path, String mimeType)
	{
		try {
			Uri uri = Uri.parse("file://" + path);
			
			Intent promptInstall = new Intent(Intent.ACTION_VIEW);
			promptInstall.setDataAndType(uri, "application/vnd.android.package-archive");
			promptInstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(promptInstall);
		} catch(Exception e) {
			e.printStackTrace();
			Toast.makeText(ctx, String.format("[ERROR (installApp)]: %s", e.getMessage()), Toast.LENGTH_LONG).show();
		}
	}
	
	/*
	* Downloads the file from the url
	*
	*@param  url         - The download url
	*@param  filename    - The filename
	*@return file        - The downloaded file
	*/
	public static File downloadUpdate(String url, String filename)
	{
		File file = null;
		
		if(!ConnectivityReceiver.isConnected(ctx))
			return file;
		
		try {
			TaskDownloadUpdate down = new TaskDownloadUpdate();
			file = down.execute(url, filename).get();
		} catch(Exception e) {
			e.printStackTrace();
			Toast.makeText(ctx, String.format("[ERROR (downloadUpdate)]: %s", e.getMessage()), Toast.LENGTH_LONG).show();
		}
		
		return file;
	}
	
	public static interface OnUpdateDetectedListener
	{
		public void onUpdateDetected(NewUpdateInfo info)
	}
	
	public static class NewUpdateInfo
	{
		public int app_version;
		public String app_updateUrl;
		public String app_versionName;
		public String app_description;
		
		public NewUpdateInfo(String url, String versionName, String description, int version)
		{
			this.app_version = version;
			this.app_versionName = versionName;
			this.app_updateUrl = url;
			this.app_description = description;
		}
	}
	
	private static class TaskUpdateChecker extends AsyncTask<String, Void, NewUpdateInfo>
	{
		private static final int CONNECT_TIMEOUT = 6000;
		private static final int READ_TIMEOUT = 3000;
		
		private ProgressDialog dlg;
		private String errMsg;
		
		@Override
		protected void onPreExecute()
		{
			super.onPreExecute();
			
			dlg = new ProgressDialog(ctx);
			dlg.setCancelable(false);
			dlg.setCanceledOnTouchOutside(false);
			dlg.setProgressDrawable(ctx.getResources().getDrawable(android.R.drawable.progress_horizontal));
			dlg.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			dlg.setMessage("Checking for new update...");
			dlg.show();
		}
		
		@Override
		protected NewUpdateInfo doInBackground(String... params)
		{
			NewUpdateInfo info = null;
			
			try {
				String str_url = params[0];
				URL url = new URL(str_url);
				
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setConnectTimeout(CONNECT_TIMEOUT);
				conn.setReadTimeout(READ_TIMEOUT);
				conn.setDoInput(true);
				conn.connect();
				
				if(conn.getResponseCode() == HttpURLConnection.HTTP_OK)
				{
					InputStream is = conn.getInputStream();
					String json = new String();
					byte[] buffer = new byte[1024];
					int len = 0;
					
					//Read the json text from the website
					while((len = is.read(buffer)) != -1)
						json += new String(buffer, 0, len);
					is.close();
					
					//Read json
					info = jsonReader.readJson(json);
				}
				
				conn.disconnect();
			} catch(Exception e) {
				e.printStackTrace();
				errMsg += e.getMessage();
			}
			
			return info;
		}
		
		@Override
		protected void onPostExecute(NewUpdateInfo result)
		{
			super.onPostExecute(result);
			
			try {
				if(dlg != null)
					dlg.dismiss();
				
				if(listener != null && errMsg == null)
					if(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode < result.app_version)
						listener.onUpdateDetected(result);
					else
						Toast.makeText(ctx, "You have the latest version!", Toast.LENGTH_LONG).show();
				else
					Toast.makeText(ctx, String.format("[ERROR]: %s", errMsg), Toast.LENGTH_LONG).show();
			} catch(Exception e) {
				e.printStackTrace();
				Toast.makeText(ctx, String.format("[ERROR (task_updatechecker)]: %s", e.getMessage()), Toast.LENGTH_LONG).show();
			}
		}
	}
	
	private static final class TaskDownloadUpdate extends AsyncTask<String, Void, File>
	{
		private ProgressDialog dlg;
		private String errMsg, mimeType;
		
		@Override
		protected void onPreExecute()
		{
			super.onPreExecute();
			
			dlg = new ProgressDialog(ctx);
			dlg.setCancelable(false);
			dlg.setCanceledOnTouchOutside(false);
			dlg.setIndeterminate(false);
			dlg.setProgressPercentFormat(NumberFormat.getPercentInstance());
			dlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			dlg.setMessage("Downloading update...");
			dlg.setMax(100);
			dlg.show();
		}
		
		@Override
		protected File doInBackground(String[] params)
		{
			File file = null;
			
			try {
				String str_url = params[0];
				String str_tag = params[1];
				
				File dest = new File(Environment.DIRECTORY_DOWNLOADS, str_tag);
				if(dest.exists())
					dest.delete();
				
				DownloadManager.Request request = new DownloadManager.Request(Uri.parse(str_url));
				request.setTitle("Downloading update");
				request.setDescription("Please wait...");
				request.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, str_tag);
				
				DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
				boolean downloading = true;
				long id = dm.enqueue(request);
				
				while(downloading)
				{
					DownloadManager.Query query = new DownloadManager.Query();
					query.setFilterById(id);
					
					Cursor cursor = dm.query(query);
					cursor.moveToFirst();
					
					int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
					if(status == DownloadManager.STATUS_SUCCESSFUL)
					{
						String filePath = cursor.getString(cursor.getColumnIndex("local_uri"));
						file = new File(filePath);
						
						mimeType = dm.getMimeTypeForDownloadedFile(id);
						if(autoInstall && errMsg == null)
							installApp(filePath, mimeType);
						
						downloading = false;
					}
					
					cursor.close();
				}
			} catch(Exception e) {
				e.printStackTrace();
				errMsg = e.getMessage();
			}
			
			return file;
		}

		@Override
		protected void onPostExecute(File result)
		{
			super.onPostExecute(result);
			
			if(dlg != null)
				dlg.dismiss();
				
			if(errMsg != null)
				Toast.makeText(ctx, String.format("[ERROR (task_downloadUpdate)]: %s", errMsg), Toast.LENGTH_LONG).show();
		}
	}
	
	private static final class ConnectivityReceiver extends BroadcastReceiver
	{
		@Override
		public void onReceive(Context ctx, Intent data)
		{
			if(isConnected(ctx))
				(new TaskUpdateChecker()).execute(updateLogUrl);
		}
		
		public static final boolean isConnected(Context ctx)
		{
			ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
			
			if(updateOnWifiOnly)
				if(cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().getType() != ConnectivityManager.TYPE_WIFI)
					return false;
				else
					return (cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI && cm.getActiveNetworkInfo().isConnected());
			else
				return (cm.getActiveNetworkInfo() != null && (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE || cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI) && cm.getActiveNetworkInfo().isConnected());
		}
	}
}
