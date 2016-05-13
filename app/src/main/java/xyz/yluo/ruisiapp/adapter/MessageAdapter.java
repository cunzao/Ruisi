package xyz.yluo.ruisiapp.adapter;

import android.app.Activity;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import xyz.yluo.ruisiapp.R;
import xyz.yluo.ruisiapp.View.ArrowTextView;
import xyz.yluo.ruisiapp.View.CircleImageView;
import xyz.yluo.ruisiapp.activity.ChatActivity;
import xyz.yluo.ruisiapp.activity.SingleArticleActivity;
import xyz.yluo.ruisiapp.activity.UserDetailActivity;
import xyz.yluo.ruisiapp.data.ListType;
import xyz.yluo.ruisiapp.data.MessageData;
import xyz.yluo.ruisiapp.listener.RecyclerViewClickListener;

/**
 * Created by free2 on 16-3-21.
 * 首页第三页
 * 回复我的 我的消息
 */
public class MessageAdapter extends RecyclerView.Adapter<BaseViewHolder>{
    private List<MessageData> DataSet;
    private RecyclerViewClickListener clickListener;
    protected Activity activity;

    public MessageAdapter(Activity activity, List<MessageData> dataSet, RecyclerViewClickListener listener) {
        DataSet = dataSet;
        this.activity = activity;
        this.clickListener = listener;
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if(viewType==1){
            return new MessageReplyListHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.user_message_list_item, parent, false));
        }else{
            return new ChangeMessageHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.change_message_item, parent, false));
        }
    }

    @Override
    public int getItemViewType(int position) {
        if(position==0){
            return 0;
        }else{
            return 1;
        }
    }

    @Override
    public void onBindViewHolder(BaseViewHolder holder, int position) {
        holder.setData(position);
    }

    @Override
    public int getItemCount() {
        return DataSet.size()+1;
    }


    //用户消息、回复我的 holder
    protected class MessageReplyListHolder extends BaseViewHolder{
        @BindView(R.id.title)
        protected TextView title;
        @BindView(R.id.time)
        protected TextView time;
        @BindView(R.id.article_user_image)
        protected CircleImageView article_user_image;
        @BindView(R.id.reply_content)
        protected ArrowTextView reply_content;
        @BindView(R.id.is_read)
        protected TextView isRead;
        //url
        public MessageReplyListHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this, itemView);
        }

        void setData(int position) {
            MessageData single_data = DataSet.get(position-1);
            title.setText(single_data.getTitle());
            time.setText(single_data.getTime());
            String imageUrl = single_data.getauthorImage();
            Picasso.with(activity).load(imageUrl).resize(40,40).centerCrop().placeholder(R.drawable.image_placeholder).into(article_user_image);
            reply_content.setText(single_data.getcontent());
            if(single_data.isRead()){
                isRead.setVisibility(View.GONE);
            }else{
                isRead.setVisibility(View.VISIBLE);
            }
        }

        @OnClick(R.id.main_item_btn_item)
        protected void item_click(){
            MessageData single_data =  DataSet.get(getAdapterPosition()-1);
            if(!single_data.isRead()){
                single_data.setRead(true);
                notifyItemChanged(getAdapterPosition());
            }
            if(ListType.MYMESSAGE==single_data.getType()){//用户消息pm
                String username = single_data.getTitle().replace("我对 ","").replace("说:","").replace(" 对我","");
                ChatActivity.open(activity,username,single_data.getTitleUrl());
                single_data.setRead(true);
            }else if(ListType.REPLAYME==single_data.getType()){//回复我的
                SingleArticleActivity.open(activity,single_data.getTitleUrl());
            }

        }
        @OnClick(R.id.article_user_image)
        protected void user_click(){
            MessageData single_data =  DataSet.get(getAdapterPosition()-1);
            String username = single_data.getTitle().replace("我对 ","").replace("说:","").replace(" 对我","").replace(" 回复了我","");
            UserDetailActivity.openWithTransitionAnimation(activity, username, article_user_image,DataSet.get(getAdapterPosition()).getauthorImage());
        }
    }

    protected class ChangeMessageHolder extends BaseViewHolder{

        @BindView(R.id.btn_change)
        protected RadioGroup btn_change;

        public ChangeMessageHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this,itemView);
            btn_change.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup radioGroup, int id) {
                    if(id==R.id.btn_reply){
                        clickListener.recyclerViewListClicked(radioGroup,0);
                    }else{
                        clickListener.recyclerViewListClicked(radioGroup,1);
                    }
                }
            });
        }

        @Override
        void setData(int position) {

        }
    }

}