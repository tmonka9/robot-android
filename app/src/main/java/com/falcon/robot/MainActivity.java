package com.falcon.robot;

import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Home: hero banner plus a grid of feature cards. */
public class MainActivity extends BaseActivity {

    private static final class Card {
        final int title;
        final int description;
        final int icon;
        final int background;
        final int navId;

        Card(int title, int description, int icon, int background, int navId) {
            this.title = title;
            this.description = description;
            this.icon = icon;
            this.background = background;
            this.navId = navId;
        }
    }

    private static final Card[] CARDS = {
            new Card(R.string.nav_robot, R.string.card_robot_desc, R.drawable.ic_robot,
                    R.drawable.bg_home_card_blue, R.id.nav_robot),
            new Card(R.string.nav_remote, R.string.card_remote_desc, R.drawable.ic_gamepad,
                    R.drawable.bg_home_card_green, R.id.nav_remote),
            new Card(R.string.nav_face, R.string.card_face_desc, R.drawable.ic_face_id,
                    R.drawable.bg_home_card_purple, R.id.nav_face),
            new Card(R.string.nav_object, R.string.card_object_desc, R.drawable.ic_scan,
                    R.drawable.bg_home_card_orange, R.id.nav_object),
            new Card(R.string.nav_voice, R.string.card_voice_desc, R.drawable.ic_mic,
                    R.drawable.bg_home_card_indigo, R.id.nav_voice),
            null, // slogan tile
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_main, R.id.nav_home, 0);
        setupColumns();
        applyHeroGradient();
        buildCardGrid();
    }

    /** Blue-to-purple gradient on the big "AI". */
    private void applyHeroGradient() {
        TextView ai = findViewById(R.id.hero_ai);
        float width = ai.getPaint().measureText(ai.getText().toString());
        ai.getPaint().setShader(new LinearGradient(0, 0, width, 0,
                0xFF4FA8FF, 0xFF8B5CF6, Shader.TileMode.CLAMP));
    }

    private void buildCardGrid() {
        LinearLayout grid = findViewById(R.id.home_grid);
        int columns = getResources().getInteger(R.integer.home_grid_columns);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap);
        LayoutInflater inflater = LayoutInflater.from(this);

        LinearLayout row = null;
        for (int i = 0; i < CARDS.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = gap;
                grid.addView(row, rowLp);
            }
            View view = inflater.inflate(R.layout.item_home_card, row, false);
            bindCard(view, CARDS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    view.getLayoutParams().height, 1f);
            if (i % columns > 0) lp.setMarginStart(gap);
            row.addView(view, lp);
        }
    }

    private void bindCard(View view, final Card card) {
        if (card == null) {
            view.setBackgroundResource(R.drawable.bg_home_card_dark);
            view.findViewById(R.id.card_body).setVisibility(View.GONE);
            view.findViewById(R.id.card_arrow).setVisibility(View.GONE);
            view.findViewById(R.id.card_slogan).setVisibility(View.VISIBLE);
            return;
        }
        view.setBackgroundResource(card.background);
        ImageView icon = view.findViewById(R.id.card_icon);
        icon.setImageResource(card.icon);
        icon.setColorFilter(color(R.color.white), PorterDuff.Mode.SRC_IN);
        ImageView deco = view.findViewById(R.id.card_deco);
        deco.setImageResource(card.icon);
        deco.setColorFilter(color(R.color.white), PorterDuff.Mode.SRC_IN);
        ((TextView) view.findViewById(R.id.card_title)).setText(card.title);
        ((TextView) view.findViewById(R.id.card_desc)).setText(card.description);
        view.setOnClickListener(v -> navigate(card.navId));
    }
}
