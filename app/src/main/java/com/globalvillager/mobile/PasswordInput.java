package com.globalvillager.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/** A password field with an explicit, accessible show/hide action. */
public final class PasswordInput extends LinearLayout {
    private final EditText input;
    private final ImageButton toggle;
    private boolean visible;

    public PasswordInput(Context context,String hint,int ink,int pale) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setSaveEnabled(false);
        GradientDrawable background=new GradientDrawable();
        background.setColor(pale);background.setCornerRadius(dp(12));setBackground(background);

        input=new EditText(context) {
            @Override public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                if(toggle!=null){toggle.setEnabled(enabled);if(!enabled)hide();}
            }
        };
        input.setHint(hint);input.setTextSize(16);input.setTextColor(ink);
        input.setSingleLine(true);input.setFilterTouchesWhenObscured(true);
        input.setPadding(dp(14),dp(10),0,dp(10));input.setBackgroundColor(Color.TRANSPARENT);
        input.setSaveEnabled(false);
        // Keep the password input classification even while revealing its text:
        // changing the transformation must not enable ordinary keyboard suggestions.
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        input.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        input.setTypeface(Typeface.DEFAULT);
        addView(input,new LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1));

        toggle=new ImageButton(context);toggle.setImageTintList(ColorStateList.valueOf(ink));
        toggle.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        toggle.setPadding(dp(12),dp(12),dp(12),dp(12));
        GradientDrawable rippleShape=new GradientDrawable();rippleShape.setColor(Color.WHITE);
        rippleShape.setCornerRadius(dp(24));
        toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22069b8b),null,rippleShape));
        toggle.setFilterTouchesWhenObscured(true);toggle.setSaveEnabled(false);
        toggle.setOnClickListener(view->{if(input.isEnabled())setVisible(!visible);});
        LayoutParams action=new LayoutParams(dp(48),dp(48));action.setMarginEnd(dp(3));
        addView(toggle,action);
        setVisible(false);
    }

    public EditText editText(){return input;}
    public void hide(){if(visible)setVisible(false);}

    private void setVisible(boolean show) {
        int start=input.getSelectionStart(),end=input.getSelectionEnd();
        visible=show;
        input.setTransformationMethod(show?null:PasswordTransformationMethod.getInstance());
        if(start>=0&&end>=0)input.setSelection(Math.min(start,input.length()),Math.min(end,input.length()));
        toggle.setImageResource(show?R.drawable.gv_visibility_off:R.drawable.gv_visibility);
        String action=AppLanguage.text(getContext(),show?R.string.password_hide:R.string.password_show);
        toggle.setContentDescription(action+": "+input.getHint());toggle.setTooltipText(action);
    }

    /** Mask every field before its activity leaves the foreground. */
    public static void hideAll(View root) {
        if(root instanceof PasswordInput password){password.hide();return;}
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)hideAll(group.getChildAt(i));
    }

    @Override protected void onDetachedFromWindow(){hide();super.onDetachedFromWindow();}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
}
