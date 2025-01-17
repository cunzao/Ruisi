package me.yluo.ruisiapp.activity;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.yluo.ruisiapp.App;
import me.yluo.ruisiapp.R;
import me.yluo.ruisiapp.adapter.BaseAdapter;
import me.yluo.ruisiapp.adapter.PostAdapter;
import me.yluo.ruisiapp.database.MyDB;
import me.yluo.ruisiapp.listener.HidingScrollListener;
import me.yluo.ruisiapp.listener.ListItemClickListener;
import me.yluo.ruisiapp.listener.LoadMoreListener;
import me.yluo.ruisiapp.model.SingleArticleData;
import me.yluo.ruisiapp.model.SingleType;
import me.yluo.ruisiapp.model.VoteData;
import me.yluo.ruisiapp.myhttp.HttpUtil;
import me.yluo.ruisiapp.myhttp.ResponseHandler;
import me.yluo.ruisiapp.myhttp.SyncHttpClient;
import me.yluo.ruisiapp.utils.DimenUtils;
import me.yluo.ruisiapp.utils.GetId;
import me.yluo.ruisiapp.utils.KeyboardUtil;
import me.yluo.ruisiapp.utils.LinkClickHandler;
import me.yluo.ruisiapp.utils.RuisUtils;
import me.yluo.ruisiapp.utils.UrlUtils;
import me.yluo.ruisiapp.widget.ArticleJumpDialog;
import me.yluo.ruisiapp.widget.MyFriendPicker;
import me.yluo.ruisiapp.widget.MyListDivider;
import me.yluo.ruisiapp.widget.emotioninput.SmileyInputRoot;
import me.yluo.ruisiapp.widget.htmlview.VoteDialog;

import static me.yluo.ruisiapp.utils.RuisUtils.getManageContent;

/**
 * Created by yang on 16-3-6.
 * 单篇文章activity
 * 一楼是楼主
 * 其余是评论
 */
public class PostActivity extends BaseActivity
        implements ListItemClickListener, LoadMoreListener.OnLoadMoreListener,
        View.OnClickListener, PopupMenu.OnMenuItemClickListener, ArticleJumpDialog.JumpDialogListener {

    private RecyclerView topicList;
    private View pageView;
    private TextView pageTextView, commentHeaderView;
    private View replyView;
    private SwipeRefreshLayout refreshLayout;

    //上一次回复时间
    private long replyTime = 0;
    private int currentPage = 1;
    private int sumPage = 1;
    private int pageViewCurrentPage = 1;
    private int clickPosition = -1;
    private boolean isGetTitle = false;
    private boolean enableLoadMore = false;
    //回复楼主的链接
    private String replyUrl = "";
    private PostAdapter adapter;
    private final List<SingleArticleData> datas = new ArrayList<>();
    private Map<String, Editable> tempDatas = new HashMap<>();
    private boolean isSaveToDataBase = false;
    private String title, authorName, tid, fid, redirectPid = "";
    private boolean showPlainText = false;
    private EditText input;
    private SmileyInputRoot rootView;
    private Map<String, String> params;

    public static void open(Context context, String url, @Nullable String author) {
        Intent intent = new Intent(context, PostActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("url", url);
        intent.putExtra("author", TextUtils.isEmpty(author) ? "null" : author);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post);
        initToolBar(true, "加载中......");

        input = findViewById(R.id.ed_comment);
        commentHeaderView = findViewById(R.id.comment_head);
        pageView = findViewById(R.id.pageView);
        replyView = findViewById(R.id.comment_view);
        showPlainText = App.showPlainText(this);

        refreshLayout = findViewById(R.id.refresh_layout);
        refreshLayout.setColorSchemeResources(R.color.red_light, R.color.green_light,
                R.color.blue_light, R.color.orange_light);

        initCommentList();
        initEmotionInput();
        Intent i = getIntent();
        String url = "";
        if (i.getData() != null) {
            // 浏览器网址跳转
            url = i.getDataString();
            if (url != null) {
                url = url.substring(App.BASE_URL_RS.length());
                Log.i("PostActivity", "浏览器跳转网址：" + url);
            }
            tid = i.getData().getQueryParameter("tid");
            authorName = "null";
        } else if (i.getExtras() != null) {
            // 正常手机睿思 APP 跳转
            Bundle b = i.getExtras();
            url = b.getString("url");
            authorName = b.getString("author");
            tid = GetId.getId("tid=", url);
        }

        if (url != null && url.contains("redirect")) {
            //处理重定向 一般是跳到指定的页数
            redirectPid = GetId.getId("pid=", url);
            if (!App.IS_SCHOOL_NET) {
                url = url + "&mobile=2";
            }
            HttpUtil.head(url, null, new ResponseHandler() {
                @Override
                public void onSuccess(byte[] response) {
                    int page = GetId.getPage(new String(response));
                    getArticleData(page);
                }

                @Override
                public void onFailure(Throwable e) {
                    getArticleData(1);
                }
            });
        } else {
            getArticleData(1);
        }

        refreshLayout.setRefreshing(true);
        refreshLayout.setOnRefreshListener(this::refresh);
    }

    private void initCommentList() {
        topicList = findViewById(R.id.topic_list);
        LinearLayoutManager mLayoutManager = new LinearLayoutManager(this);
        topicList.setLayoutManager(mLayoutManager);
        adapter = new PostAdapter(this, this, datas);
        topicList.addItemDecoration(new MyListDivider(this, MyListDivider.VERTICAL));
        topicList.addOnScrollListener(new LoadMoreListener(mLayoutManager, this, 8));
        topicList.setAdapter(adapter);
    }

    private void initEmotionInput() {
        View smileyBtn = findViewById(R.id.btn_emotion);
        View btnMore = findViewById(R.id.btn_more);
        View btnSend = findViewById(R.id.btn_send);
        btnSend.setOnClickListener(this);
        rootView = findViewById(R.id.root);
        rootView.initSmiley(input, smileyBtn, btnSend);
        rootView.setMoreView(LayoutInflater.from(this).inflate(R.layout.my_smiley_menu, null), btnMore);
        pageTextView = findViewById(R.id.pageText);
        pageTextView.setOnClickListener(this);

        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                if (commentHeaderView.getVisibility() == View.GONE) {
                    // reply lz
                    if (datas.size() > 0) {
                        setupReplyView(datas.get(0));
                    }
                }
                //Toast.makeText(getApplicationContext(), "focus", Toast.LENGTH_LONG).show();
            } else {
                clearReplyView();
            }
        });


        topicList.setOnTouchListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                KeyboardUtil.hideKeyboard(input);
                rootView.hideSmileyContainer();
            }
            return false;
        });

        //监听滑动事件
        //滚动更新页码
        topicList.addOnScrollListener(new HidingScrollListener(DimenUtils.dip2px(PostActivity.this, 32)) {
            @Override
            public void onHide() {
                showPageView();

                //隐藏toolbar
                //toolBar.animate().translationY(-toolBar.getHeight()).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(200);
            }

            @Override
            public void onShow() {
                showReplyView(false, null);

                //显示toolbar
                //toolBar.animate().translationY(0).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(200);
            }
        });

        topicList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                if (dy > 0) {
                    int position = ((LinearLayoutManager) topicList.getLayoutManager())
                            .findLastVisibleItemPosition();
                    if (position >= datas.size()) {
                        // loading more showing loading...
                        return;
                    }

                    pageViewCurrentPage = datas.get(position).page;
                    String newPageText = pageViewCurrentPage + " / " + sumPage + "页";
                    if (!newPageText.contentEquals(pageTextView.getText())) {
                        pageTextView.setText(newPageText);
                    }
                }
            }
        });


        MyFriendPicker.attach(this, input);

        findViewById(R.id.btn_star).setOnClickListener(this);
        findViewById(R.id.btn_link).setOnClickListener(this);
        findViewById(R.id.btn_share).setOnClickListener(this);
        findViewById(R.id.btn_pre_page).setOnClickListener(this);
        findViewById(R.id.btn_next_page).setOnClickListener(this);
    }

    private void showPageView() {
        pageView.setVisibility(View.VISIBLE);
        replyView.setVisibility(View.GONE);
        commentHeaderView.setVisibility(View.GONE);

        clearReplyView();
    }

    private void clearReplyView() {
        if (View.GONE != commentHeaderView.getVisibility()) {
            commentHeaderView.setVisibility(View.GONE);
            SingleArticleData d = (SingleArticleData) input.getTag();
            if (d == null && datas.size() > 0) {
                d = datas.get(0);
            }
            if (d == null) {
                return;
            }

            // back up
            tempDatas.put(d.replyUrl, input.getText());
            input.setText(null);
        }
    }

    private void setupReplyView(SingleArticleData data) {
        Editable editable = tempDatas.get(data.replyUrl);

        // current
        SingleArticleData d = (SingleArticleData) input.getTag();
        if (d == null && datas.size() > 0) {
            d = datas.get(0);
        }

        if (d == null || !TextUtils.equals(data.replyUrl, d.replyUrl)) {
            // not the same
            if (d != null) {
                // backup old
                if (!TextUtils.isEmpty(input.getText())) {
                    tempDatas.put(d.replyUrl, input.getText());
                }
            }
            input.setText(editable);
            d = data;
        } else {
            // reopen the same target
            if (!TextUtils.isEmpty(input.getText())) {
                // has value replace new state
                tempDatas.put(d.replyUrl, input.getText());
            } else {
                // no current use old if the same recover
                input.setText(editable);
            }
        }

        input.setSelection(input.getText().length());
        input.setTag(d);

        if (d.type == SingleType.CONTENT || d.type == SingleType.COMMENT) {
            commentHeaderView.setText("回复 " + d.index + " " + d.username + "："
                    + d.textContent.substring(0, Math.min(20, d.textContent.length())));
        } else {
            commentHeaderView.setText("回复 楼主 " + (isGetTitle ? title : ""));
        }
        commentHeaderView.setVisibility(View.VISIBLE);
    }

    private void showReplyView(boolean focus, SingleArticleData data) {
        pageView.setVisibility(View.GONE);
        replyView.setVisibility(View.VISIBLE);
        if (focus) {
            setupReplyView(data);
            showReplyKeyboard();
        }
    }

    @Override
    public void onLoadMore() {
        //触发加载更多
        if (enableLoadMore) {
            enableLoadMore = false;

            if (datas.size() > 0) {
                currentPage = datas.get(datas.size() - 1).page;
                if (currentPage <= 0) {
                    currentPage = 1;
                }
            }

            if (currentPage < sumPage) {
                currentPage++;
            }
            getArticleData(currentPage);
        }
    }

    public void refresh() {
        refreshLayout.setRefreshing(true);
        adapter.changeLoadMoreState(BaseAdapter.STATE_LOADING);
        //数据填充
        datas.clear();
        adapter.notifyDataSetChanged();
        getArticleData(1);
    }

    //文章一页的html 根据页数 tid
    private void getArticleData(final int page) {
        String url;
        url = UrlUtils.getSingleArticleUrl(tid, page, false);
        Log.i("=====", "load post " + url);

        HttpUtil.get(url, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                //if (api) {
                //    new DealWithArticleDataApi(PostActivity.this).execute(response);
                //} else {
                new DealWithArticleData(PostActivity.this).execute(new String(response));
                //}
            }

            @Override
            public void onFailure(Throwable e) {
                if (e != null && e == SyncHttpClient.NeedLoginError) {
                    isLogin();
                    showToast("此帖需要登录才能查看");
                    return;
                }
                enableLoadMore = true;
                refreshLayout.postDelayed(() -> refreshLayout.setRefreshing(false), 500);
                adapter.changeLoadMoreState(BaseAdapter.STATE_LOAD_FAIL);
                showToast("加载失败(Error -1)");
            }
        });
    }

    @Override
    public void onListItemClick(View v, final int position) {
        switch (v.getId()) {
            case R.id.html_text:
            case R.id.btn_reply_cz:
                if (isLogin()) {
                    SingleArticleData single = datas.get(position);
//                    Intent i = new Intent(PostActivity.this, ReplyCzActivity.class);
//                    i.putExtra("islz", single.uid == datas.get(0).uid);
//                    i.putExtra("data", single);
//                    startActivityForResult(i, 20);
                    showReplyView(true, single);
                }
                break;
            case R.id.need_loading_item:
                refresh();
                break;
            case R.id.btn_more:
                clickPosition = position;
                PopupMenu popup = new PopupMenu(this, v);
                popup.setOnMenuItemClickListener(this);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.menu_post_more, popup.getMenu());

                //判断是不是自己
                if (!datas.get(position).canManage
                        && (!App.isLogin(this)
                        || !App.getUid(this).equals(datas.get(position).uid))) {
                    popup.getMenu().removeItem(R.id.tv_edit);
                }

                //如果有管理权限，则显示除了关闭之外的全部按钮
                if (!datas.get(position).canManage) {
                    popup.getMenu().removeGroup(R.id.menu_manege);
                }

                popup.show();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        // 更多按钮里面的选项被点击
        switch (menuItem.getItemId()) {
            case R.id.tv_copy:
                adapter.copyItem(clickPosition);
                break;
            case R.id.tv_edit:
                Intent i = new Intent(this, EditActivity.class);
                i.putExtra("PID", datas.get(clickPosition).pid);
                i.putExtra("TID", tid);
                startActivityForResult(i, 10);
                break;
            case R.id.tv_remove:
                showDialog("删除帖子!", "请输入删帖理由", "删除", clickPosition, App.MANAGE_TYPE_DELETE);
                break;
            //TODO 处理点击事件
            case R.id.tv_block:
                showDialog("屏蔽帖子！", "请输入屏蔽或者解除", "确定", clickPosition, App.MANAGE_TYPE_BLOCK);
                break;
            case R.id.tv_close:
                showDialog("打开或者关闭主题", "按照格式\n功能(打开/关闭)|yyyy-MM-dd|hh:mm\n"
                                + "填写,例:\n关闭|2018-04-03|04:03\n时间不填为永久",
                        "提交", clickPosition, App.MANAGE_TYPE_CLOSE);
                break;
            case R.id.tv_warn:
                showDialog("警告用户！", "请输入警告或者解除", "确定", clickPosition, App.MANAGE_TYPE_WARN);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == 10) {
                //编辑Activity返回
                Bundle b = data.getExtras();
                String title = b.getString("TITLE", "");
                String content = b.getString("CONTENT", "");
                if (clickPosition == 0 && !TextUtils.isEmpty(title)) {
                    datas.get(0).title = title;
                }
                datas.get(clickPosition).content = content;
                adapter.notifyItemChanged(clickPosition);
            } else if (requestCode == 20) {
                //回复层主返回
                replyTime = System.currentTimeMillis();
                if (currentPage == sumPage) {
                    onLoadMore();
                }
            }

        }
    }


    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_send:
                replyLzOrCz();
                break;
            case R.id.btn_star:
                if (isLogin()) {
                    showToast("正在收藏帖子...");
                    starTask(view);
                }
                break;
            case R.id.btn_link:
                String url = UrlUtils.getSingleArticleUrl(tid, currentPage, false);
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText(null, url));
                Toast.makeText(this, "已复制链接到剪切板", Toast.LENGTH_SHORT).show();
                break;
            case R.id.btn_share:
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, title + UrlUtils.getSingleArticleUrl(tid, currentPage, App.IS_SCHOOL_NET));
                shareIntent.setType("text/plain");
                //设置分享列表的标题，并且每次都显示分享列表
                startActivity(Intent.createChooser(shareIntent, "分享到文章到:"));
                break;
            case R.id.btn_pre_page:
                if (currentPage > 1) {
                    jumpPage(currentPage - 1);
                }
                break;
            case R.id.btn_next_page:
                if (currentPage < sumPage) {
                    jumpPage(currentPage + 1);
                }
                break;
            case R.id.pageText:
                ArticleJumpDialog dialogFragment = new ArticleJumpDialog();
                dialogFragment.setCurrentPage(currentPage);
                dialogFragment.setMaxPage(sumPage);
                dialogFragment.show(getSupportFragmentManager(), "jump_page");
                break;
            default:
                break;
        }
    }

    @Override
    public void jumpComfirmClick(DialogFragment dialog, int page) {
        // 翻页弹窗回调
        jumpPage(page);
    }

    /**
     * 处理数据类 后台进程
     * 解析文章列表
     */
    private class DealWithArticleData extends AsyncTask<String, Void, List<SingleArticleData>> {

        private String errorText = "";
        private int pageLoad = 1;
        private final Context context;

        DealWithArticleData(Context context) {
            this.context = context;
        }

        @Override
        protected List<SingleArticleData> doInBackground(String... params) {
            errorText = "";
            List<SingleArticleData> tepdata = new ArrayList<>();
            String htmlData = params[0];
            if (!isGetTitle) {
                int headStart = htmlData.indexOf("<title>");
                int headEnd = htmlData.indexOf("</title>");
                if (headStart > 0 && headEnd > headStart) {
                    title = htmlData.substring(headStart + 7, headEnd);
                    if (title.contains("-")) {
                        title = title.substring(0, title.indexOf("-"));
                    }
                }
                isGetTitle = true;
            }

            int bodyStartIndex = htmlData.indexOf("<body");
            int bodyEndIndex = htmlData.lastIndexOf("</body>");
            if (bodyStartIndex < 0 || bodyEndIndex < 0) { //估计是防采集开了 抓不到内容
                errorText = "获取内容失败！\n可能是睿思管理员开了防采集请联系管理员解决！";
                return null;
            }
            String content = htmlData.substring(bodyStartIndex, bodyEndIndex + 7);
            Document doc = Jsoup.parse(content);

            Elements as = doc.select(".footer a");
            if (as.size() > 1) {
                String hash = GetId.getHash(as.get(1).attr("href"));
                Log.v("hash", "hash is " + hash);
                App.setHash(PostActivity.this, hash);
            }

            //判断错误
            Elements elements = doc.select(".postlist");
            if (elements.size() <= 0) {
                errorText = RuisUtils.getErrorText(content);
                if (TextUtils.isEmpty(errorText)) {
                    errorText = "暂无数据...";
                }
                return tepdata;
            }

            //获得回复楼主的url
            if (TextUtils.isEmpty(replyUrl)) {
                String s = elements.select("form#fastpostform").attr("action");
                if (!TextUtils.isEmpty(s)) {
                    replyUrl = s;
                    //获得板块ID用于请求数据
                    fid = GetId.getId("fid=", replyUrl);
                }
            }

            //获取总页数 和当前页数
            if (doc.select(".pg").text().length() > 0) {
                if (doc.select(".pg").text().length() > 0) {
                    pageLoad = GetId.getNumber(doc.select(".pg").select("strong").text());
                    int n = GetId.getNumber(doc.select(".pg").select("span").attr("title"));
                    if (n > 0 && n > sumPage) {
                        sumPage = n;
                    }
                }
            }

            Elements postlist = elements.select("div[id^=pid]");
            int size = postlist.size();
            for (int i = 0; i < size; i++) {
                Element temp = postlist.get(i);
                String pid = temp.attr("id").substring(3);
                int uid = Integer.parseInt(GetId.getId("uid=", temp.select("span[class=avatar]").select("img").attr("src")));
                Elements userInfo = temp.select("ul.authi");
                // 手机版commentIndex拿到的原始数据是"楼层 管理"
                String commentIndex = userInfo.select("li.grey").select("em").first().text();
                String username = userInfo.select("a[href^=home.php?mod=space&uid=]").text();
                if (TextUtils.isEmpty(username) || uid == 0) {
                    // 匿名的情况
                    username = userInfo.select("li.grey").select("b").first().text();
                }
                boolean canManage = false;
                // 判别是否对该帖子是否有管理权限
                if (App.isLogin(context)) {
                    if (App.IS_SCHOOL_NET) {
                        // 校园网
                        Elements es = temp.select("div.plc.cl").select("div.display.pi")
                                .select("ul.authi").select("li.grey.rela").select("em");
                        if (es != null && es.size() != 0) {
                            canManage = "管理".equals(es.first()
                                    .select("a").text());

                        }
                    } else {
                        // 校外网
                        Elements es = userInfo.select("li.grey.rela").select("em");
                        if (es != null && es.size() != 0) {
                            canManage = "管理".equals(es.first()
                                    .select("a").text());
                        }
                    }
                }
                // 手机版postTime拿到的原始数据是"管理 收藏 时间"
                String postTime = userInfo.select("li.grey.rela").text()
                        .replace("收藏", "")
                        .replace("管理", "");
                String replyCzUrl = temp.select(".replybtn").select("input").attr("href");
                Elements contentels = temp.select(".message");
                // 处理未点击添加到帖子里的图片
                if (false) {
                    // 校园网环境下是“mbn savephotop”，目前并没有使用校内地址进行抓取
                    Elements othImgs = temp.select("div.mbn.savephotop");
                } else {
                    // 外网环境下是“img_list cl vm”
                    Elements otherImgs = temp.select("a.orange");
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < otherImgs.size(); j++) {
                        String a = otherImgs.get(j).removeClass("error_text").html();
                        if (a.contains("fixwr")) {
                            sb.append(a.replace("fixwr", "fixnone"));
                            sb.append("<br>");
                        }
                    }
                    contentels.html(contentels.html() + sb.toString());
                }
                //去除script
                contentels.select("script").remove();

                //是否移除所有样式
                if (showPlainText) {
                    //移除所有style
                    contentels.select("[style]").removeAttr("style");
                    contentels.select("font").removeAttr("color").removeAttr("size").removeAttr("face");
                }

                //处理代码
                for (Element codee : contentels.select(".blockcode")) {
                    codee.html("<code>" + codee.html().trim() + "</code>");
                }

                //处理引用
                for (Element codee : contentels.select("blockquote")) {
                    int start = codee.html().indexOf("发表于");
                    if (start > 0) {
                        Elements es = codee.select("a");
                        if (es.size() > 0 && es.get(0).text().contains("发表于")) {
                            String user = es.get(0).text().substring(0, es.get(0).text().indexOf(" "));
                            int sstart = codee.html().indexOf("<br>", start) + 4;
                            codee.html(user + ":" + codee.html().substring(sstart).replaceAll("<br>", " "));
                            break;
                        }
                    }
                }

                // 处理未点击添加到帖子里的图片
                // http://rsbbs.xidian.edu.cn/forum.php?mod=viewthread&tid=952530&page=1&mobile=2
                final Elements extraImages = temp.select("ul[class^=img_]").select("li");
                if (extraImages.size() > 0) {
                    contentels.append(extraImages.html());
                }

                SingleArticleData data;
                if (pageLoad == 1 && i == 0) {//内容
                    //处理投票
                    VoteData d = null;
                    int maxSelection = 1;
                    Elements vote = contentels.select("form[action^=forum.php?mod=misc&action=votepoll]");
                    if (vote.size() > 0 && vote.select("input[type=submit]").size() > 0) {// 有且有投票权
                        if (vote.text().contains("单选投票")) {
                            maxSelection = 1;
                        } else if (vote.text().contains("多选投票")) {
                            int start = vote.text().indexOf("多选投票");
                            maxSelection = GetId.getNumber(vote.text().substring(start, start + 20));
                        }

                        Elements ps = vote.select("p");
                        List<Pair<String, String>> options = new ArrayList<>();
                        for (Element p : ps) {
                            if (p.select("input").size() > 0) {
                                options.add(new Pair<>(p.select("input").attr("value"),
                                        p.select("label").text()));
                            }
                        }

                        if ("radio".equals(ps.select("input").get(0).attr("type"))) {
                            maxSelection = 1;
                        }

                        vote.select("input[type=submit]").get(0).html("<a href=\"" +
                                LinkClickHandler.VOTE_URL + "\">点此投票</a><br>");
                        d = new VoteData(vote.attr("action"), options, maxSelection);
                    }

                    if (TextUtils.isEmpty(replyCzUrl)) {
                        replyCzUrl = replyUrl;
                    }

                    data = new SingleArticleData(SingleType.CONTENT, title, uid,
                            username, postTime, commentIndex, replyCzUrl, contentels.html().trim(),
                            pid, pageLoad, canManage);
                    data.textContent = contentels.text().trim();

                    data.vote = d;
                    authorName = username;
                    if (!isSaveToDataBase) {
                        //插入数据库
                        MyDB myDB = new MyDB(PostActivity.this);
                        myDB.handSingleReadHistory(tid, title, authorName);
                        isSaveToDataBase = true;
                    }
                } else {//评论
                    data = new SingleArticleData(SingleType.COMMENT, title, uid,
                            username, postTime, commentIndex, replyCzUrl,
                            contentels.html().trim(), pid, pageLoad, canManage);
                    data.textContent = contentels.text().trim();
                }
                tepdata.add(data);
            }
            return tepdata;
        }

        @Override
        protected void onPostExecute(List<SingleArticleData> tepdata) {
            enableLoadMore = true;
            refreshLayout.postDelayed(() -> refreshLayout.setRefreshing(false), 500);

            if (tepdata == null) {
                if (!TextUtils.isEmpty(errorText)) {
                    adapter.setLoadFailedText(errorText);
                }
                setTitle("加载失败");
                adapter.changeLoadMoreState(BaseAdapter.STATE_LOAD_FAIL);
                //new Handler().postDelayed(() -> finish(), 800);
                return;
            }

            if (isGetTitle) {
                setTitle(title);
            } else {
                setTitle("帖子正文");
            }

            if (pageLoad != currentPage) {
                currentPage = pageLoad;
            }


            if (tepdata.size() == 0) {
                adapter.changeLoadMoreState(BaseAdapter.STATE_LOAD_NOTHING);
                return;
            }

            int startsize = datas.size();
            if (datas.size() == 0) {
                datas.addAll(tepdata);
            } else {
                String strindex = datas.get(datas.size() - 1).index;
                if (TextUtils.isEmpty(strindex)) {
                    strindex = "-1";
                } else if ("沙发".equals(strindex)) {
                    strindex = "1";
                } else if ("板凳".equals(strindex)) {
                    strindex = "2";
                } else if ("地板".equals(strindex)) {
                    strindex = "3";
                }
                int index = GetId.getNumber(strindex);
                for (int i = 0; i < tepdata.size(); i++) {
                    String strindexp = tepdata.get(i).index;
                    if ("沙发".equals(strindexp)) {
                        strindexp = "1";
                    } else if ("板凳".equals(strindex)) {
                        strindexp = "2";
                    } else if ("地板".equals(strindex)) {
                        strindexp = "3";
                    }
                    int indexp = GetId.getNumber(strindexp);
                    if (indexp > index) {
                        datas.add(tepdata.get(i));
                    }
                }
            }

            if (currentPage < sumPage) {
                adapter.changeLoadMoreState(BaseAdapter.STATE_LOADING);
            } else {
                adapter.changeLoadMoreState(BaseAdapter.STATE_LOAD_NOTHING);
            }

            if (datas.size() > 0 && (datas.get(0).type != SingleType.CONTENT) &&
                    (datas.get(0).type != SingleType.HEADER)) {
                datas.add(0, new SingleArticleData(SingleType.HEADER, title,
                        0, null, null, null, null,
                        null, null, pageLoad));
            }
            int add = datas.size() - startsize;
            if (startsize == 0) {
                adapter.notifyDataSetChanged();
            } else {
                adapter.notifyItemRangeInserted(startsize, add);
            }

            //打开的时候移动到指定楼层
            if (!TextUtils.isEmpty(redirectPid)) {
                for (int i = 0; i < datas.size(); i++) {
                    if (!TextUtils.isEmpty(datas.get(i).pid)
                            && datas.get(i).pid.equals(redirectPid)) {
                        topicList.scrollToPosition(i);
                        break;
                    }
                }
                redirectPid = "";
            }

            pageTextView.setText(currentPage + " / " + sumPage + "页");
        }

    }

    /**
     * 收藏帖子
     */
    private void starTask(final View v) {
        final String url = UrlUtils.getStarUrl(tid);
        Map<String, String> params = new HashMap<>();
        params.put("favoritesubmit", "true");
        HttpUtil.post(url, params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                if (res.contains("成功") || res.contains("您已收藏")) {
                    showToast("收藏成功");
                    if (v != null) {
                        final ImageView mv = (ImageView) v;
                        mv.postDelayed(() -> mv.setImageResource(R.drawable.ic_star_32dp_yes), 300);
                    }
                }
            }
        });
    }


    private void startBlock(int position) {
        String url = "forum.php?mod=topicadmin&action=banpost"
                + "&fid=" + fid
                + "&tid=" + tid
                + "&topiclist[]=" + datas.get(position).pid
                + "&mobile=2&inajax=1";
        params = null;
        HttpUtil.get(url, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                Document document = RuisUtils.getManageContent(response);
                params = RuisUtils.getForms(document, "topicadminform");
            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误！请重试");
            }
        });
    }

    private void startWarn(int position) {
        if (App.IS_SCHOOL_NET) {
            // computer
            params = new HashMap<>();
            params.put("fid", fid);
            params.put("page", "1");
            params.put("tid", tid);
            params.put("handlekey", "mods");
            params.put("topiclist[]", datas.get(position).pid);
            params.put("reason", " 手机版主题操作");
        } else {
            String url = "forum.php?mod=topicadmin&action=warn&fid=" + fid
                    + "&tid=" + tid
                    + "&operation=&optgroup=&page=&topiclist[]=" + datas.get(position).pid + "&mobile=2&inajax=1";
            //url = forum.php?mod=topicadmin&action=warn&fid=72&tid=922824&handlekey=mods&infloat=yes&nopost=yes&r0.8544855790245922&inajax=1
            params = null;
            HttpUtil.get(url, new ResponseHandler() {
                @Override
                public void onSuccess(byte[] response) {
                    Document document = getManageContent(response);
                    params = RuisUtils.getForms(document, "topicadminform");
                }

                @Override
                public void onFailure(Throwable e) {
                    super.onFailure(e);
                }
            });
        }
    }

    private void startClose() {
        String url = "";
        if (App.IS_SCHOOL_NET) {
            url = "forum.php?mod=topicadmin&action=moderate&fid=" + fid + "&moderate[]=" + tid + "&handlekey=mods" +
                    "&infloat=yes&nopost=yes&from=" + tid + "&inajax=1";
        } else {
            url = "forum.php?mod=topicadmin&action=moderate&fid=" + fid
                    + "&moderate[]=" + tid + "&from=" + tid
                    + "&optgroup=4&mobile=2";
        }
        params = null;
        HttpUtil.get(url, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                Document document = RuisUtils.getManageContent(response);
                params = RuisUtils.getForms(document, "moderateform");
                params.put("redirect", "");
            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误！请重试");
            }
        });
    }

    private void warnUser(int position, String s) {
        if ("警告".equals(s)) {
            params.put("warned", "1");
        } else {
            params.put("warned", "0");
        }
        HttpUtil.post(UrlUtils.getWarnUserUrl(), params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                if (res.contains("成功")) {
                    showToast("帖子操作成功，刷新帖子即可看到效果");
                } else {
                    showToast("管理操作失败,我也不知道哪里有问题");
                }
            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误，操作失败！");
            }
        });
    }

    private void blockReply(int position, String s) {
        if ("屏蔽".equals(s)) {
            params.put("banned", "1");
        } else {
            params.put("banned", "0");
        }
        HttpUtil.post(UrlUtils.getBlockReplyUrl(), params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                if (res.contains("成功")) {
                    showToast("帖子操作成功，刷新帖子即可看到效果");
                } else {
                    showToast("管理操作失败,我也不知道哪里有问题");
                }
            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误，操作失败！");
            }
        });
    }

    // 打开或者关闭帖子
    private void closeArticle(String[] str) {
        if (str.length == 3) {
            params.put("expirationclose", str[1] + " " + str[2]);
        } else {
            params.put("expirationclose", "");
        }
        if ("打开".equals(str[0])) {
            params.put("operations[]", "open");
        } else if ("关闭".equals(str[0])) {
            params.put("operations[]", "close");
        }
        params.put("reason", "手机版主题操作");
        HttpUtil.post(UrlUtils.getCloseArticleUrl(), params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                if (res.contains("成功")) {
                    showToast(str[0] + "帖子操作成功，刷新帖子即可看到效果");
                } else {
                    showToast("管理操作失败,我也不知道哪里有问题");
                }
            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误，" + str[0] + "帖子失败！");
            }
        });
    }

    private void startDelete(int position) {
        if (App.IS_SCHOOL_NET) {
            params = new HashMap<>();
            params.put("fid", fid);
            params.put("handlekey", "mods");
            if (datas.get(position).type == SingleType.CONTENT) {
                // 主题
                params.put("moderate[]", tid);
                params.put("operations[]", "delete");
            } else {
                // 评论
                params.put("topiclist[]", datas.get(position).pid);
                params.put("tid", tid);
                params.put("page", (1 + position / 10) + "");
            }
        } else {
            String url;
            // 以下仅仅针对手机版做了测试
            if (datas.get(position).type == SingleType.CONTENT) {
                //删除整个帖子
                url = "forum.php?mod=topicadmin&action=moderate&fid=" + fid
                        + "&moderate[]=" + tid + "&operation=delete&optgroup=3&from="
                        + tid + "&mobile=2&inajax=1";
            } else {
                //删除评论
                url = "forum.php?mod=topicadmin&action=delpost&fid=" + fid
                        + "&tid=" + tid + "&operation=&optgroup=&page=&topiclist[]="
                        + datas.get(position).pid + "&mobile=2&inajax=1";
            }
            params = null;
            HttpUtil.get(url, new ResponseHandler() {
                @Override
                public void onSuccess(byte[] response) {
                    Document document = RuisUtils.getManageContent(response);
                    if (datas.get(position).type == SingleType.CONTENT) {
                        params = RuisUtils.getForms(document, "moderateform");
                    } else {
                        params = RuisUtils.getForms(document, "topicadminform");
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    super.onFailure(e);
                    showToast("网络错误！");
                }
            });
        }
    }

    //删除帖子或者回复
    private void removeItem(final int pos, String reason) {
        params.put("redirect", "");
        params.put("reason", reason);
        HttpUtil.post(UrlUtils.getDeleteReplyUrl(datas.get(pos).type), params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                //Log.e("result", res);
                if (res.contains("成功")) {
                    if (datas.get(pos).type == SingleType.CONTENT) {
                        showToast("主题删除成功");
                        finish();
                    } else {
                        showToast("回复删除成功");
                        datas.remove(pos);
                        adapter.notifyItemRemoved(pos);
                    }
                } else {
                    int start = res.indexOf("<p>");
                    int end = res.indexOf("<", start + 5);
                    String ss = res.substring(start + 3, end);
                    showToast(ss);
                }

            }

            @Override
            public void onFailure(Throwable e) {
                super.onFailure(e);
                showToast("网络错误,删除失败！");
            }
        });
    }

    //回复楼主或者层主
    private void replyLzOrCz() {
        if (!(isLogin() && checkTime() && checkInput())) {
            return;
        }
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("回复中");
        dialog.setMessage("请稍后......");
        dialog.show();

        String s = getPreparedReply(this, input.getText().toString());
        Map<String, String> params = new HashMap<>();
        params.put("message", s);
        params.put("inajax", "1");

        SingleArticleData d = (SingleArticleData) input.getTag();
        if (d != null && !TextUtils.isEmpty(d.replyUrl) && !TextUtils.equals(d.replyUrl, replyUrl)) {
            // load post para
            HttpUtil.get(d.replyUrl, new ResponseHandler() {
                @Override
                public void onSuccess(byte[] response) {
                    Document document = Jsoup.parse(new String(response));
                    Elements els = document.select("#postform");

                    Map<String, String> postForm = RuisUtils.getForms(document, "postform");
                    postForm.remove("message");
                    params.putAll(postForm);
                    params.put("inajax", "1");

                    String newPostUrl = els.attr("action");
                    sendReplyPost(newPostUrl, params, dialog);
                }

                @Override
                public void onFailure(Throwable e) {
                    handleReply(false, "");
                    dialog.dismiss();
                }
            });
        } else {
            sendReplyPost(replyUrl, params, dialog);
        }
    }

    private void sendReplyPost(String postUrl, Map<String, String> params, ProgressDialog dialog) {
        Log.i("=====", "reply: " + postUrl);

        HttpUtil.post(postUrl, params, new ResponseHandler() {
            @Override
            public void onSuccess(byte[] response) {
                String res = new String(response);
                Log.i("=====", "reply res: " + res);
                handleReply(true, res);
            }

            @Override
            public void onFailure(Throwable e) {
                handleReply(false, "");
            }

            @Override
            public void onFinish() {
                super.onFinish();
                dialog.dismiss();
            }
        });
    }

    public static String getPreparedReply(Context context, String text) {
        int len = text.getBytes(StandardCharsets.UTF_8).length;
        SharedPreferences shp = PreferenceManager.getDefaultSharedPreferences(context);
        if (shp.getBoolean("setting_show_tail", false)) {
            String texttail = shp.getString("setting_user_tail", "无尾巴").trim();
            if (!"无尾巴".equals(texttail)) {
                texttail = "     " + texttail;
                text += texttail;
            }
        }

        //字数补齐补丁
        if (len < 13) {
            int need = 14 - len;
            for (int i = 0; i < need; i++) {
                text += " ";
            }
        }

        return text;
    }

    private void handleReply(boolean isok, String res) {
        if (isok) {
            if (res.contains("成功") || res.contains("层主")) {
                Toast.makeText(this, "回复发表成功", Toast.LENGTH_SHORT).show();
                input.setText(null);
                replyTime = System.currentTimeMillis();
                KeyboardUtil.hideKeyboard(input);
                rootView.hideSmileyContainer();
                if (sumPage == 1) {
                    refresh();
                } else if (currentPage == sumPage) {
                    onLoadMore();
                }
            } else if (res.contains("您两次发表间隔")) {
                showToast("您两次发表间隔太短了......");
            } else if (res.contains("主题自动关闭")) {
                showLongToast("此主题已关闭回复,无法回复");
            } else if (!TextUtils.isEmpty(RuisUtils.getRuisiReqAjaxError(res))) {
                showLongToast(RuisUtils.getRuisiReqAjaxError(res));
            } else {
                showToast("由于未知原因发表失败");
            }
        } else {
            Toast.makeText(this, "发表失败请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    //跳页
    private void jumpPage(int to) {
        //1 find page
        int finded = -1;
        for (int i = 0; i < datas.size(); i++) {
            if (datas.get(i).page == to) {
                finded = i;
                break;
            }
        }

        if (finded >= 0) {
            topicList.scrollToPosition(finded);
            currentPage = to;
            pageTextView.setText(currentPage + " / " + sumPage + "页");
        } else {
            datas.clear();
            adapter.notifyDataSetChanged();
            getArticleData(to);
        }
    }

    private boolean checkInput() {
        String s = input.getText().toString();
        if (TextUtils.isEmpty(s)) {
            showToast("你还没写内容呢!");
            return false;
        } else {
            return true;
        }
    }

    private boolean checkTime() {
        if (System.currentTimeMillis() - replyTime > 2000) {
            return true;
        } else {
            showToast("您两次发表间隔太短了!");
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (!rootView.hideSmileyContainer()) {
            super.onBackPressed();
        }
    }

    //显示投票dialog
    public void showVoteView() {
        if (datas.get(0).type == SingleType.CONTENT) {
            VoteData d = datas.get(0).vote;
            if (d != null) {
                VoteDialog.show(this, d);
                return;
            }

        }
        showToast("投票数据异常无法投票");
    }

    //用户点击了回复链接
    //显示软键盘
    public void showReplyKeyboard() {
        KeyboardUtil.showKeyboard(input);
    }

    // 管理按钮点击后的确认窗口
    public void showDialog(String title, String message, String posStr,
                           int position, int type) {
        final EditText edt = new EditText(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setView(edt)
                .setCancelable(true);
        switch (type) {
            case App.MANAGE_TYPE_EDIT:
                // nothing to do
                break;
            case App.MANAGE_TYPE_DELETE:
                builder.setPositiveButton(posStr, (dialog, which) -> {
                    if (!"".equals(edt.getText().toString())) {
                        removeItem(position, edt.getText().toString());
                    } else {
                        showToast("请输入删帖理由!");
                    }
                });
                startDelete(position);
                break;
            case App.MANAGE_TYPE_BLOCK:
                builder.setPositiveButton(posStr, (dialog, which) -> {
                    if (!"".equals(edt.getText().toString())
                            && ("屏蔽".equals(edt.getText().toString())
                            || "解除".equals(edt.getText().toString()))) {
                        blockReply(position, edt.getText().toString());
                    } else {
                        showToast("请输入屏蔽或者解除");
                    }
                });
                startBlock(position);
                break;
            case App.MANAGE_TYPE_WARN:
                builder.setPositiveButton(posStr, (dialog, which) -> {
                    if (!"".equals(edt.getText().toString())
                            && ("警告".equals(edt.getText().toString())
                            || "解除".equals(edt.getText().toString()))) {
                        warnUser(position, edt.getText().toString());
                    } else {
                        showToast("请输入警告或者解除");
                    }
                });
                startWarn(position);
                break;
            case App.MANAGE_TYPE_CLOSE:
                builder.setPositiveButton(posStr, (dialog, which) -> {
                    String[] time = edt.getText().toString().split("\\|");
                    if (("打开".equals(time[0]) || "关闭".equals(time[0])
                            && (time.length == 1 || time.length == 3))) {
                        closeArticle(time);
                    } else {
                        showToast("输入格式错误，请重新输入");
                    }
                });
                startClose();
                break;
            default:
                break;
        }
        builder.create().show();
    }


}
