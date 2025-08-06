package com.screenomics;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // MindPulse tab temporarily disabled - only show ScreenLife
        return new ScreenLifeFragment();
        /*
        switch (position) {
            case 0:
                return new ScreenLifeFragment();
            case 1:
                return new MindPulseFragment();
            default:
                return new ScreenLifeFragment();
        }
        */
    }

    @Override
    public int getItemCount() {
        return 1; // Only ScreenLife tab (MindPulse temporarily disabled)
        // return 2; // Two tabs: ScreenLife and MindPulse
    }

    public String getTabTitle(int position) {
        // MindPulse tab temporarily disabled - only show ScreenLife
        return "ScreenLife";
        /*
        switch (position) {
            case 0:
                return "ScreenLife";
            case 1:
                return "MindPulse";
            default:
                return "ScreenLife";
        }
        */
    }
}