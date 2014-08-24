/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.marekasf.troughput;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.Semaphore;
import java.util.stream.IntStream;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;
import org.marekasf.troughput.histogram.AdaptiveHistogram;

public class XYHistogramChart extends ApplicationFrame
{
	private static final long serialVersionUID = 1052668809088392899L;

	public static void display(final AdaptiveHistogram h, final String title)
	{

		final XYHistogramChart demo = new XYHistogramChart(h, title);
		demo.pack();
		RefineryUtilities.centerFrameOnScreen(demo);
		demo.setVisible(true);
		final Semaphore semaphore = new Semaphore(0);
		demo.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(final WindowEvent we)
			{
				semaphore.release();
			}
		});
		try
		{
			semaphore.acquire();
		}
		catch (final InterruptedException e)
		{
			//
		}
	}

	public XYHistogramChart(final AdaptiveHistogram h, final String title)
	{

		super(title);
		final XYSeries series = new XYSeries(title);

		IntStream.rangeClosed(0, 100).forEach(i -> series.add(i, h.getValueForPercentile(i)));

		final XYSeriesCollection data = new XYSeriesCollection(series);
		final JFreeChart chart = ChartFactory.createXYLineChart("XY Histogram Chart " + title, "X", "Y", data,
				PlotOrientation.VERTICAL, true, true, false);

		final ChartPanel chartPanel = new ChartPanel(chart);
		chartPanel.setPreferredSize(new java.awt.Dimension(500, 270));
		setContentPane(chartPanel);
	}
}
