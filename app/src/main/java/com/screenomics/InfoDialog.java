package com.screenomics;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;


import androidx.fragment.app.DialogFragment;

public class InfoDialog extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("UW ASSIST Lab MindPulse")
                .setMessage("Developed by researchers from the University of Wisconsin-Madison ASSIST Lab, MindPulse is a smartphone application that captures high frequency screenshots of your smartphone use. When in use, the data is securely encrypted and sent over to the research team at UW Madison.\n\n" +
                        "This application is meant for research purposes only, and if you are not a consenting participant of a research study conducted by University of Wisconsin-Madison ASSIST Lab researchers, please delete this application immediately.")
                .setNeutralButton("OK", null);
        return builder.create();
    }
}
