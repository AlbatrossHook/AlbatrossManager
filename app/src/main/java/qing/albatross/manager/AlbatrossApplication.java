package qing.albatross.manager;

import android.app.Application;

import qing.albatross.manager.data.ConfigManager;

public class AlbatrossApplication extends Application {


  @Override
  public void onCreate() {
    super.onCreate();
    //提前建好单例：广播命令可能在界面从未打开过的情况下触发，
    //而 ServerDatabaseHelper 里有直接取 ConfigManager 单例的地方
    ConfigManager.getInstance(this);
  }
}
