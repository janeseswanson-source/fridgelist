package com.fridgelist.core.data.meals

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.MealToItemsRequest
import com.fridgelist.core.model.MealToItemsResponse
import com.fridgelist.core.model.SuggestMealsRequest
import com.fridgelist.core.model.SuggestMealsResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 meal ideas (F11). Network only; the screen says so when offline. */
interface MealRepository {
    suspend fun suggest(request: SuggestMealsRequest): SuggestMealsResponse

    suspend fun toItems(request: MealToItemsRequest): MealToItemsResponse
}

@Singleton
class CallableMealRepository @Inject constructor(
    private val callables: CallableClient,
) : MealRepository {
    override suspend fun suggest(request: SuggestMealsRequest): SuggestMealsResponse =
        callables.call("suggestMeals", request, SuggestMealsRequest.serializer(), SuggestMealsResponse.serializer())

    override suspend fun toItems(request: MealToItemsRequest): MealToItemsResponse =
        callables.call("mealToItems", request, MealToItemsRequest.serializer(), MealToItemsResponse.serializer())
}
