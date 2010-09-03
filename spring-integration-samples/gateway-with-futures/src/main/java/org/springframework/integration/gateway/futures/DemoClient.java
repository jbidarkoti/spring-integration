/*
 * Copyright 2002-2010 the original author or authors.
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
package org.springframework.integration.gateway.futures;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

/**
 * @author Oleg Zhurakousky
 *
 */
public class DemoClient {

	private static ExecutorService executor = Executors.newFixedThreadPool(100);
	private static int timeout = 20;
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		ApplicationContext ac = new FileSystemXmlApplicationContext("src/main/resources/META-INF/spring/integration/*.xml");
		MathServiceGateway mathService = ac.getBean("mathService", MathServiceGateway.class);
		Map<Integer, Future<Integer>> results = new HashMap<Integer, Future<Integer>>();
		Random random = new Random();
		for (int i = 0; i < 100; i++) {
			int number = random.nextInt(200);
			Future<Integer> result = mathService.multiplyByTwo(number);
			results.put(number, result);
		}
		for (final Map.Entry<Integer, Future<Integer>> resultEntry : results.entrySet()) {
			executor.execute(new Runnable() {
				public void run() {			
					int[] result = processFuture(resultEntry);
					
					if (result[1] == -1){
						System.out.println("Multiplying " + result[0] + " should be easy. You should be able to multiply any number < 100 by 2 in your head");
					} else if (result[1] == -2){
						System.out.println("Multiplication of " + result[0] + " by 2 is can not be accomplished in " + timeout + " seconds");
					} else {
						System.out.println("Result of multiplication of " + result[0] + " by 2 is " + result[1]);
					}
				}
			});		
		}
	}
	
	public static int[] processFuture(Map.Entry<Integer, Future<Integer>> resultEntry){
		int originalNumber = resultEntry.getKey();
		Future<Integer> result = resultEntry.getValue();
		try {
			int finalResult =  result.get(timeout, TimeUnit.SECONDS);
			return new int[]{originalNumber, finalResult};
		} catch (ExecutionException e) {
			return new int[]{originalNumber, -1};
		} catch (TimeoutException tex){
			return new int[]{originalNumber, -2};
		} catch (Exception ex){
			System.out.println();
			// ignore
		}
		return null;
	}
}