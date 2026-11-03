package top.saymzx.easycontrol.app;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;

import top.saymzx.easycontrol.app.databinding.ActivityActiveBinding;
import top.saymzx.easycontrol.app.databinding.ItemLoadingBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class ActiveActivity extends Activity {

  private ActivityActiveBinding activityActiveBinding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ViewTools.setStatusAndNavBar(this);
    ViewTools.setLocale(this);
    activityActiveBinding = ActivityActiveBinding.inflate(this.getLayoutInflater());
    setContentView(activityActiveBinding.getRoot());
    setButtonListener();
    // 绘制UI
    drawUi();
  }

  private void drawUi() {
    activityActiveBinding.key.setText(AppData.setting.getActiveKey());
    activityActiveBinding.url.setOnClickListener(v -> PublicTools.startUrl(this, "https://gitee.com/mingzhixianweb/easycontrol/blob/master/DONATE.md"));
  }

  private void setButtonListener() {
  }

  @Override
  public void onBackPressed() {
  }
}
