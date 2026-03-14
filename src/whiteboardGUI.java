/*******
Marko Simic
Whiteboard GUI Application
3/10/25
*******************/
import javax.swing.JFrame;
import javax.swing.JPanel;

public class whiteboardGUI { // class for the whiteboard app

    public static void main(String[] args) { // main method

        JFrame frame = new JFrame("Whiteboard GUI Application"); //JFrame created
        frame.setSize(800, 600); // window size set to 800x600
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // close operation set to exit on close

        CanvasPanel canvas = new CanvasPanel(); // new canvas panel object created to draw on
        frame.add(canvas); // panel added to the frame

        frame.setVisible(true); // frame set to visible so the window appears when the program runs
    }
}