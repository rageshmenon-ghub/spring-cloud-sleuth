/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.scheduling;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.cloud.sleuth.IdGenerator;
import org.springframework.cloud.sleuth.MilliSpan;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Trace;
import org.springframework.cloud.sleuth.TraceManager;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Aspect that creates a new Span for running threads executing methods annotated with
 * {@link Scheduled} annotation. For every execution of scheduled method a new trace will
 * be started.
 *
 * @author Tomasz Nurkewicz, 4financeIT
 * @author Michal Chmielarz, 4financeIT
 * @author Marcin Grzejszczak, 4financeIT
 * @author Spencer Gibb
 *
 * @see TraceManager
 */
@Aspect
public class TraceSchedulingAspect {

	private final TraceManager trace;
	private final IdGenerator idGenerator;

	public TraceSchedulingAspect(TraceManager trace, IdGenerator idGenerator) {
		this.trace = trace;
		this.idGenerator = idGenerator;
	}

	@Around("execution (@org.springframework.scheduling.annotation.Scheduled  * *.*(..))")
	public Object traceBackgroundThread(final ProceedingJoinPoint pjp) throws Throwable {
		final Span span = this.trace.isTracing() ? this.trace.getCurrentSpan()
				: MilliSpan.builder().begin(System.currentTimeMillis())
						.traceId(this.idGenerator.create()).spanId(this.idGenerator.create())
						.build();
		Trace scope = this.trace.startSpan(pjp.toShortString(), span);
		try {
			return pjp.proceed();
		}
		finally {
			this.trace.close(scope);
		}
	}
}