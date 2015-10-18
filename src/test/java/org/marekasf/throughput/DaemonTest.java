/*
 * [y] hybris Platform
 *
 * Copyright (c) 2000-2015 hybris AG
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of hybris
 * ("Confidential Information"). You shall not disclose such Confidential
 * Information and shall use it only in accordance with the terms of the
 * license agreement you entered into with hybris.
 */
package org.marekasf.throughput;

import org.junit.Test;
import org.marekasf.troughput.ThroughputRunner;

import rx.Observable;

public class DaemonTest
{
	@Test
	public void daemon()
	{
		final ThroughputRunner.Daemon daemon = ThroughputRunner.Builder.create().testTimeInSeconds(0).threads(2).stress(false) //
				.action(() -> Observable.defer(() -> sleep())) //
				.graph(false).daemon();

		daemon.start();
		sleep();

		for (int i =0; i<10; ++i)
		{
			daemon.log();
			daemon.errors();
			daemon.histogram();
			daemon.stats();
			daemon.log();
			sleep();
		}

		daemon.stop();
	}

	private Observable<Boolean> sleep()
	{
		try
		{
			Thread.sleep(100);
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException("Daemon interrupted", e);
		}
		return Observable.just(true);
	}
}
