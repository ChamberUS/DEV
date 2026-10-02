package panel.ui;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;

/** Gráficos escuros simples. Valores nulos viram "sem dados" na tela chamadora. */
public final class Charts {
    private Charts() {
    }

    public static Node bar(String yLabel, List<String> categories, List<Double> values) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(230);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        for (int i = 0; i < categories.size(); i++) {
            if (values.get(i) != null) {
                s.getData().add(new XYChart.Data<>(categories.get(i), values.get(i)));
            }
        }
        chart.getData().add(s);
        return chart;
    }

    public static Node line(String yLabel, List<String> categories, List<Double> values) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setForceZeroInRange(true);
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(260);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        for (int i = 0; i < categories.size(); i++) {
            s.getData().add(new XYChart.Data<>(categories.get(i), values.get(i)));
        }
        chart.getData().add(s);
        return chart;
    }
}
