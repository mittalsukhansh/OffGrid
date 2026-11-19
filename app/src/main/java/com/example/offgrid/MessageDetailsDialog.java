package com.example.offgrid;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.function.Consumer;

/** Small UI adapter; availability and execution are revalidated against durable storage. */
final class MessageDetailsDialog {
    static void show(
            Activity activity,
            String text,
            boolean media,
            List<PrivateActions.TransferCommand> commands,
            Consumer<PrivateActions.TransferCommand> action) {
        AlertDialog.Builder dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(media ? "Attachment transfer" : "Delivery details")
                        .setMessage(text + "\n\nSnapshot — reopen to refresh.")
                        .setNegativeButton("Close", null);
        if (!commands.isEmpty())
            dialog.setPositiveButton(
                    "Controls",
                    (d, w) -> {
                        String[] labels = new String[commands.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = label(commands.get(i));
                        new AlertDialog.Builder(activity)
                                .setTitle("Transfer controls")
                                .setItems(
                                        labels,
                                        (choices, which) ->
                                                confirm(activity, commands.get(which), action))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    });
        dialog.show();
    }

    private static String label(PrivateActions.TransferCommand command) {
        switch (command) {
            case PAUSE:
                return "Pause sending";
            case RESUME:
                return "Resume sending";
            case CANCEL:
                return "Cancel local sending";
            default:
                return "Send again as new attachment";
        }
    }

    private static void confirm(
            Activity activity,
            PrivateActions.TransferCommand command,
            Consumer<PrivateActions.TransferCommand> action) {
        boolean cancel = command == PrivateActions.TransferCommand.CANCEL;
        if (!cancel && command != PrivateActions.TransferCommand.SEND_AGAIN) {
            action.accept(command);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(cancel ? "Cancel local sending?" : "Send a new attachment?")
                .setMessage(
                        cancel
                                ? "Stops this phone from sending more. Bytes already relayed may"
                                      + " still arrive. Use unsend separately to request removal."
                                : "Starts from zero with a new message ID. The contact may already"
                                      + " have a copy of the earlier transfer.")
                .setPositiveButton("Continue", (d, w) -> action.accept(command))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
