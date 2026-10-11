package byx.demo;
/** Separate jpackage entrypoint; never PanelApp/Main or Service bootstrap. */
public final class DemoMain {public static void main(String[] args){if(args.length!=0)throw new IllegalArgumentException("DEMO accepts no mode/backend/debug arguments");javafx.application.Application.launch(DemoApp.class);}}