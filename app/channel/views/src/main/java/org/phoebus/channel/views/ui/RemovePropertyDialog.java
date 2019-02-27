package org.phoebus.channel.views.ui;

import java.io.IOException;
import java.util.Collection;

import org.phoebus.channelfinder.Property;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

/**
 * A dialog for removing a property to a list of channels
 * 
 * @author Kunal Shroff
 *
 */
public class RemovePropertyDialog extends Dialog<Property> {

    public RemovePropertyDialog(final Node parent, final Collection<String> tags) {
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResizable(true);
        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(this.getClass().getResource("SelectEntity.fxml"));
        try {
            getDialogPane().setContent(loader.load());
            SelectEntityController controller = loader.getController();
            controller.setAvaibleOptions(tags);
            setResultConverter(button -> {
                return button == ButtonType.OK
                        ? Property.Builder.property(controller.getSelectedOption()).build()
                        : null;
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
