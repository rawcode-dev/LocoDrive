package com.locodrive.ui;

/**
 * Base class for all wizard step controllers.
 * Provides access to the MainController and lifecycle hooks.
 */
public abstract class BaseStepController {

    protected MainController mainController;

    public void setMainController(MainController mc) {
        this.mainController = mc;
    }

    /**
     * Called when this step becomes visible.
     * Override to refresh data or run animations.
     */
    public void onEnter() {}
}
