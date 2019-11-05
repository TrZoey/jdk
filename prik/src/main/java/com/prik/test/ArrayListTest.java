package com.prik.test;

import java.util.ArrayList;

/**
 * @author prik
 * @date 2019/10/28
 */
public class ArrayListTest {

    public static void main(String[] args) {

        ArrayList<String> list = new ArrayList<String>();
        list.add("1");
        list.add("2");
        list.add("2");
        list.add("3");
        list.add("4");

        ArrayList<String> removeList = new ArrayList<String>();
        removeList.add("2");
        removeList.add("4");

        list.removeAll(removeList);
    }
}
